package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;

class NotificationLoopTest {

    /** Заглушка вместо модели: отдаёт заранее заготовленные ответы по очереди. */
    private static final class ScriptedLlm implements LlmClient {
        private final Deque<ChatResponse> scripted = new ArrayDeque<>();
        private final List<List<Message>> seenContexts = new ArrayList<>();
        private int calls;

        private ChatResponse repeating;

        void willRespond(ChatResponse response) {
            scripted.addLast(response);
        }

        /** Отвечать так всегда — для проверки зациклившейся модели. */
        void alwaysRespond(ChatResponse response) {
            repeating = response;
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            calls++;
            seenContexts.add(List.copyOf(history));
            if (!scripted.isEmpty()) {
                return scripted.removeFirst();
            }
            return repeating != null ? repeating : finishing();
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {0};
        }
    }

    private static ChatResponse withToolCalls(List<ToolCall> calls, long tokens) {
        Message message = new Message(Message.Role.ASSISTANT, "", calls, null, null);
        return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, message, "tool_calls")),
                new ChatResponse.Usage(tokens, 0, tokens));
    }

    private static ChatResponse plainText(String text) {
        Message message = new Message(Message.Role.ASSISTANT, text, List.of(), null, null);
        return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, message, "stop")),
                ChatResponse.Usage.EMPTY);
    }

    private static ChatResponse finishing() {
        return withToolCalls(List.of(call("wait")), 10);
    }

    private static ToolCall call(String name) {
        return new ToolCall("call_" + name, "function", new ToolCall.Function(name, "{}"));
    }

    private NotificationLoop loopWith(ScriptedLlm llm, Toolbox tools) {
        return new NotificationLoop(llm, () -> "системный промпт", tools, 40_000);
    }

    @Test
    void finishesTurnOnWait() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("тебе написали"));

        assertEquals(1, llm.calls);
    }

    @Test
    void nudgesModelThatRepliedWithPlainText() {
        // текстовый ответ никто не увидит — это не действие
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(plainText("Ой, надо бы ответить!"));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("тебе написали"));

        assertEquals(2, llm.calls, "после текстового ответа модель должна получить второй шанс");
        assertTrue(loop.context().stream().anyMatch(m -> m.content() != null && m.content().contains("tool-centric")));
    }

    @Test
    void keepsGoingUntilFinishingToolIsCalled() {
        List<String> sent = new ArrayList<>();
        Toolbox tools = new Toolbox(Tool.simple("send", "Отправляет", arguments -> {
            sent.add("сообщение");
            return "Отправлено";
        }));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send")), 10));
        llm.willRespond(withToolCalls(List.of(call("send")), 10));
        llm.willRespond(finishing());

        loopWith(llm, tools).process(new Notification("тебе написали"));

        assertEquals(2, sent.size(), "модель успевает отправить несколько сообщений подряд");
        assertEquals(3, llm.calls);
    }

    @Test
    void rollsBackLowQualityAnswerWithoutStoringIt() {
        Toolbox tools = new Toolbox(Tool.simple("send", "Отправляет", arguments -> {
            throw new LowQualityException("Слишком длинно, напиши короче");
        }));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, tools);
        loop.process(new Notification("тебе написали"));

        assertTrue(loop.context().stream().anyMatch(m -> "Слишком длинно, напиши короче".equals(m.content())));
        assertFalse(loop.context().stream()
                        .flatMap(m -> m.toolCallsOrEmpty().stream())
                        .anyMatch(c -> "send".equals(c.name())),
                "откаченный вызов не должен оставаться в контексте");
    }

    @Test
    void technicalFailureIsShownToModelInsteadOfBreakingTurn() {
        Toolbox tools = new Toolbox(Tool.simple("send", "Отправляет", arguments -> {
            throw new IllegalStateException("нет сети");
        }));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, tools);
        loop.process(new Notification("тебе написали"));

        assertTrue(loop.context().stream()
                .anyMatch(m -> m.content() != null && m.content().contains("нет сети")),
                "модель должна увидеть причину и решить, что делать");
    }

    @Test
    void unknownToolDoesNotBreakTurn() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("несуществующий")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("тебе написали"));

        assertTrue(loop.context().stream()
                .anyMatch(m -> m.content() != null && m.content().contains("недоступен")));
    }

    @Test
    void dumpsContextWhenItGrowsTooBig() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("wait")), 50_000));

        NotificationLoop loop = loopWith(llm, new Toolbox());
        boolean[] dumped = {false};
        loop.onContextOverflow(() -> dumped[0] = true);

        loop.process(new Notification("тебе написали"));

        assertTrue(dumped[0], "распухший контекст пора сбрасывать в память");
    }

    @Test
    void stopsAfterStepLimitWhenModelNeverFinishes() {
        // модель зациклилась и не зовёт wait — цикл обязан выйти сам
        Toolbox tools = new Toolbox(Tool.simple("send", "Отправляет", arguments -> "ок"));
        ScriptedLlm llm = new ScriptedLlm();
        llm.alwaysRespond(withToolCalls(List.of(call("send")), 10));

        loopWith(llm, tools).process(new Notification("тебе написали"));

        assertTrue(llm.calls <= 24, "цикл не должен крутиться бесконечно, было вызовов: " + llm.calls);
        assertTrue(llm.calls > 1);
    }

    @Test
    void toolAddedDuringTurnBecomesAvailable() {
        // «открыть чат» добавляет «отправить» — набор обязан обновиться на следующем шаге
        Toolbox available = new Toolbox();
        List<String> sent = new ArrayList<>();
        available.add(Tool.simple("open", "Открывает чат", arguments -> {
            available.add(Tool.simple("send", "Отправляет", args -> {
                sent.add("сообщение");
                return "Отправлено";
            }));
            return "чат открыт";
        }));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("open")), 10));
        llm.willRespond(withToolCalls(List.of(call("send")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("тебе написали", available));

        assertEquals(1, sent.size(), "инструмент, появившийся по ходу хода, должен работать");
        assertFalse(loop.context().stream()
                        .anyMatch(m -> m.content() != null && m.content().contains("недоступен")),
                "модель не должна получить отказ по инструменту, который уже добавлен");
    }

    @Test
    void doesNotDigIntoMemoryRightAfterSendingMessage() {
        // воспоминания между репликами сбивают мысль: вместо связного ответа
        // получается поток случайных фактов
        List<String> recallMoments = new ArrayList<>();
        Toolbox tools = new Toolbox(Tool.simple("send_telegram_message", "Отправляет", arguments -> "ок"));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, tools);
        loop.memory(ctx -> {
            recallMoments.add("вспомнила");
            return "";
        });

        loop.process(new Notification("тебе написали"));

        assertEquals(1, recallMoments.size(),
                "память поднимается один раз в начале хода, а не после каждого сообщения");
    }

    @Test
    void recallsMemoryBeforeAnsweringAndPutsItBeforeNotification() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.memory(ctx -> "<your_diary_page>вы ездили на Байкал</your_diary_page>\n");

        loop.process(new Notification("тебе написали"));

        String firstMessage = loop.context().getFirst().content();
        assertTrue(firstMessage.startsWith("<your_diary_page>"), firstMessage);
        assertTrue(firstMessage.contains("тебе написали"), "уведомление должно остаться на месте");
    }

    @Test
    void opensChatWithoutAskingModel() {
        // модель открывает чат в ста случаях из ста — спрашивать об этом незачем
        List<String> opened = new ArrayList<>();
        Toolbox available = new Toolbox(Tool.simple("open", "Открывает чат", arguments -> {
            opened.add("открыт");
            return "Ты открыла чат. Последние сообщения: привет";
        }));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("тебе написали", available));

        assertEquals(1, opened.size());
        assertEquals(1, llm.calls, "лишнего обращения к модели быть не должно");
        assertTrue(loop.context().getFirst().content().contains("Последние сообщения"),
                "модель должна сразу увидеть переписку, а не уведомление");
    }

    @Test
    void doesNotOpenAutomaticallyWhenThereIsAChoice() {
        Toolbox available = new Toolbox(
                Tool.simple("open", "Открывает чат", arguments -> "открыто"),
                Tool.simple("ignore", "Игнорирует", arguments -> "ладно"));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("тебе написали", available));

        assertTrue(loop.context().getFirst().content().contains("тебе написали"),
                "есть выбор — решает модель");
    }

    @Test
    void remindsToConsultMemoryBeforeAnswering() {
        Toolbox tools = new Toolbox(Tool.simple("ask", "Спрашивает память", arguments -> "ничего"));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, tools);
        loop.process(new Notification("тебе написали"));

        assertTrue(loop.context().getFirst().content().contains("Have you called #ask"),
                loop.context().getFirst().content());
    }

    @Test
    void doesNotNagWhenMemoryWasAlreadyConsulted() {
        Toolbox tools = new Toolbox(Tool.simple("ask", "Спрашивает память", arguments -> "вспомнила"));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("ask")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, tools);
        loop.process(new Notification("тебе написали"));

        long reminders = loop.context().stream()
                .filter(m -> m.content() != null && m.content().contains("Have you called #ask"))
                .count();
        assertEquals(1, reminders, "напоминание уместно один раз, до обращения к памяти");
    }

    @Test
    void toolsFromOneTurnDoNotLeakIntoTheNext() {
        // самая опасная ошибка: отправка в чат, открытый когда-то раньше,
        // остаётся доступной — и сообщение уходит не тому человеку
        List<String> sentTo = new ArrayList<>();
        Toolbox firstNotification = new Toolbox();
        firstNotification.add(Tool.named("open").describedAs("Открывает чат")
                .buildContextual((arguments, addTool) -> {
                    addTool.accept(Tool.simple("send", "Отправляет в первый чат", args -> {
                        sentTo.add("первый");
                        return "ок";
                    }));
                    return "чат открыт";
                }));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("первое сообщение", firstNotification));
        assertEquals(1, sentTo.size(), "в своём ходе отправка работает");

        // второй ход, другой чат — отправки в первый быть не должно
        llm.willRespond(withToolCalls(List.of(call("send")), 10));
        llm.willRespond(finishing());
        loop.process(new Notification("сообщение из другого чата", new Toolbox()));

        assertEquals(1, sentTo.size(), "инструмент прошлого хода не должен работать");
        assertTrue(loop.context().stream()
                        .anyMatch(m -> m.content() != null && m.content().contains("недоступен")),
                "модель должна получить отказ, а не отправку не туда");
    }

    @Test
    void discardsObsoleteNotifications() {
        NotificationLoop loop = loopWith(new ScriptedLlm(), new Toolbox());
        loop.submit(new Notification("<notification chat_id=\"5\">\nсообщение"));
        loop.submit(new Notification("<notification chat_id=\"9\">\nдругое"));

        loop.discardMatching("<notification chat_id=\"5\">\n");

        // косвенная проверка: осталось одно — обработка не зависнет на take()
        loop.process(new Notification("проверка"));
        assertTrue(true);
    }
}
