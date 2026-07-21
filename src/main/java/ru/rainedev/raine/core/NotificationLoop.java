package ru.rainedev.raine.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;
import ru.rainedev.raine.memory.Memory;

/**
 * Ядро поведения: очередь уведомлений и обработка каждого до тех пор,
 * пока Raine не решит, что делать больше нечего.
 * <p>
 * Ход всегда заканчивается вызовом {@code wait} или {@code pause}. Ответ
 * обычным текстом действием не считается: его никто не увидит, и модели
 * об этом напоминают.
 */
public final class NotificationLoop {

    private static final Logger log = LoggerFactory.getLogger(NotificationLoop.class);

    /** Страховка от зацикливания: живой ход столько шагов не занимает. */
    private static final int MAX_STEPS_PER_NOTIFICATION = 24;

    private static final String ASK_REMINDER = """
            [system] Have you called #ask yet this turn? If the message involves personal topics, past events, \
            questions, or people you know — call #ask BEFORE send_telegram_message.""";

    private static final String ASK_MISSED = """
            [system] Note: you sent a message without consulting #ask this turn. Next time, call #ask before \
            send_telegram_message to enrich your response with memories and context.""";

    private static final String BE_TOOL_CENTRIC = """
            Nice thoughts! However you should be tool-centric. Make sure you made tool calls. \
            The message you provided is not visible to anyone but you. Call #wait if you are unsure.""";

    private final LinkedBlockingDeque<Notification> queue = new LinkedBlockingDeque<>();
    private final List<Message> context = new ArrayList<>();

    private final LlmClient llm;
    private final SystemPrompt systemPrompt;
    private final Toolbox alwaysAvailable;
    private final long contextTokenLimit;

    /** Что делать с распухшим контекстом. Позже здесь появится запись в дневник. */
    private Runnable onContextOverflow = () -> {};

    private Memory memory = Memory.NONE;

    private Rest rest;

    /** Ход закончился — она отложила телефон. */
    private Runnable onIdle = () -> { };

    private java.util.function.Consumer<Notification> onNotificationDone = notification -> { };

    @FunctionalInterface
    public interface SystemPrompt {
        String text();
    }

    public NotificationLoop(LlmClient llm, SystemPrompt systemPrompt, Toolbox alwaysAvailable, long contextTokenLimit) {
        this.llm = llm;
        this.systemPrompt = systemPrompt;
        this.alwaysAvailable = alwaysAvailable;
        this.contextTokenLimit = contextTokenLimit;
    }

    public void onContextOverflow(Runnable action) {
        this.onContextOverflow = action;
    }

    public void memory(Memory memory) {
        this.memory = memory;
    }

    public void rest(Rest rest) {
        this.rest = rest;
    }

    public void onIdle(Runnable action) {
        this.onIdle = action;
    }

    public void onNotificationDone(java.util.function.Consumer<Notification> action) {
        this.onNotificationDone = action;
    }

    public void submit(Notification notification) {
        queue.addLast(notification);
    }

    /** Срочное уведомление — попадёт в начало очереди. */
    public void submitFirst(Notification notification) {
        queue.addFirst(notification);
    }

    /** Убирает устаревшие уведомления — например, когда чат уже открыт вручную. */
    public void discardMatching(String substring) {
        queue.removeIf(notification -> notification.text().contains(substring));
    }

    public List<Message> context() {
        return List.copyOf(context);
    }

    public void clearContext() {
        context.clear();
    }

    /** Бесконечный цикл. Прерывается только остановкой потока. */
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (rest != null) {
                    // отходит перед тем, как взяться за уведомление: накопившееся
                    // прочитается разом, как это и бывает у человека
                    rest.maybeRest();
                }
                Notification notification = queue.take();
                process(notification);
                onNotificationDone.accept(notification);
                onIdle.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("Не удалось обработать уведомление", e);
                recoverFrom(e);
            }
        }
    }

    /** Обработка одного уведомления целиком — до wait/pause. */
    public void process(Notification notification) {
        String text = openImmediately(notification).orElse(notification.text());
        context.add(Message.user(text + "\nCurrent time: " + Instant.now() + " UTC"));

        boolean recallMemories = true;
        boolean askedThisTurn = false;
        for (int step = 0; step < MAX_STEPS_PER_NOTIFICATION; step++) {
            if (recallMemories) {
                recallIntoContext();
            }
            // набор пересобирается каждый шаг: инструменты появляются по ходу дела.
            // открытие чата добавляет отправку — со снимком, снятым заранее, её бы не было
            Toolbox tools = withFinishingTools(notification.tools());
            if (!askedThisTurn && tools.names().contains("ask")) {
                // напоминание встаёт прямо перед выбором действия, чтобы модель его заметила
                appendToLast(ASK_REMINDER);
            }
            ChatResponse response = llm.chat(systemPrompt.text(), context, tools.asJson());
            List<ToolCall> calls = response.toolCalls();

            // мысли и намерения — то, ради чего вообще стоит смотреть в лог
            if (response.text() != null && !response.text().isBlank()) {
                log.info("Мысли: {}", response.text().strip());
            }
            if (!calls.isEmpty()) {
                log.info("Действия: {}", calls.stream().map(ToolCall::name).toList());
            }

            if (calls.isEmpty()) {
                // ответ текстом действием не является — напоминаем и пробуем снова
                log.warn("Модель не вызвала ни одного инструмента, шаг {}", step);
                context.add(Message.user(BE_TOOL_CENTRIC));
                continue;
            }

            List<Message> results;
            try {
                results = tools.invoke(calls);
            } catch (LowQualityException e) {
                // откат: сообщение модели в контекст не попадает, вместо него — подсказка
                log.info("Ответ низкого качества, пробуем иначе: {}", e.getMessage());
                context.add(Message.user(e.getMessage()));
                if (response.totalTokens() > contextTokenLimit) {
                    log.warn("Контекст переполнен, а подходящий ответ не найден — сбрасываем");
                    onContextOverflow.run();
                    return;
                }
                continue;
            }

            context.add(response.firstMessage().orElseThrow());
            context.addAll(results);

            // после отправки сообщения за воспоминаниями не ходим: подмешанные между
            // репликами, они сбивают мысль, и вместо связного ответа выходит поток
            // случайных фактов
            recallMemories = calls.stream().noneMatch(call -> "send_telegram_message".equals(call.name()));
            askedThisTurn |= calls.stream().anyMatch(call -> "ask".equals(call.name()));
            if (!askedThisTurn && !recallMemories) {
                appendToLast(ASK_MISSED);
            }

            if (calls.stream().anyMatch(call -> Toolbox.FINISHING.contains(call.name()))) {
                if (response.totalTokens() >= contextTokenLimit) {
                    onContextOverflow.run();
                }
                return;
            }

            if (!tools.isEmpty()) {
                appendToLast("\nWhat's your next action? Use a `tool` to act. Use #ask to consult with your "
                        + "knowledge database. The following tools available: " + String.join(", ", tools.names()));
            }
        }

        log.warn("Достигнут предел шагов — прекращаем ход принудительно");
    }

    private void appendToLast(String addition) {
        Message last = context.getLast();
        context.set(context.size() - 1, last.withContent(last.content() + "\n" + addition));
    }

    /**
     * Если уведомление предлагает единственное действие «открыть» без параметров,
     * выполняем его сразу. Модель открывает чат в ста случаях из ста, и спрашивать
     * об этом — лишнее обращение к ней на каждое сообщение.
     */
    private java.util.Optional<String> openImmediately(Notification notification) {
        var tools = notification.tools().all();
        if (tools.size() != 1) {
            return java.util.Optional.empty();
        }
        Tool only = tools.iterator().next();
        if (!"open".equals(only.name()) || !only.parameters().path("properties").isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            log.debug("Открываю чат сразу, не спрашивая модель");
            // инструменты, которые откроет чат, попадают в набор уведомления —
            // вместе с ним они и исчезнут
            return java.util.Optional.of(
                    only.handler().call(only.parameters().objectNode(), notification.tools()::add));
        } catch (RuntimeException e) {
            log.warn("Не удалось открыть чат сразу: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /** Воспоминания встают перед последним сообщением — как будто вспомнилось до ответа. */
    private void recallIntoContext() {
        String recalled = memory.recall(context);
        if (recalled.isEmpty()) {
            return;
        }
        Message last = context.getLast();
        context.set(context.size() - 1, last.withContent(recalled + last.content()));
    }

    /** wait и pause доступны всегда: иначе ход невозможно закончить. */
    private Toolbox withFinishingTools(Toolbox notificationTools) {
        Toolbox tools = new Toolbox();
        alwaysAvailable.all().forEach(tools::add);
        notificationTools.all().forEach(tools::add);
        tools.add(Tool.simple("wait", "Wait until further notifications", arguments -> "Success"));
        tools.add(Tool.simple("pause", "Pauses the conversation", arguments -> "Success"));
        return tools;
    }

    /** Повреждённый JSON означает испорченный контекст — его проще выбросить целиком. */
    private void recoverFrom(RuntimeException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase();
        if (message.contains("json")) {
            log.warn("Контекст повреждён, сбрасываем");
            context.clear();
        }
    }
}
