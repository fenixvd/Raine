package ru.rainedev.raine.tools;

import it.tdlight.jni.TdApi;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.LowQualityException;
import ru.rainedev.raine.core.Numbers;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.core.Toolbox;
import ru.rainedev.raine.phone.ChatKind;
import ru.rainedev.raine.phone.ChatScreen;
import ru.rainedev.raine.phone.MessageFormatter;
import ru.rainedev.raine.telegram.Telegram;
import ru.rainedev.raine.telegram.TelegramActions;

/** Инструменты для работы с Telegram. Состав зависит от того, где Raine сейчас находится. */
public final class TelegramTools {

    private static final Logger log = LoggerFactory.getLogger(TelegramTools.class);

    /** Длиннее — уже не разговорное сообщение, а простыня. */
    private static final int MAX_MESSAGE_LENGTH = 700;

    /** Столько сообщений подряд — уже не разговор, а поток. */
    private static final int MAX_IN_A_ROW = 10;

    /** Насколько настойчиво просить разбивать длинные реплики. */
    private static final double SPLIT_NUDGE = 0.3;

    /** Больше этого числа сообщений при открытии чата не подгружается никогда. */
    private static final int HISTORY_DEPTH = 30;

    /** Сколько чатов показывать в списке. */
    private static final int CHAT_LIST_DEPTH = 30;

    private static final int SEARCH_LIMIT = 20;

    /** Больше уже не поиск, а вываливание переписки в контекст. */
    private static final int MAX_SEARCH_LIMIT = 50;

    /** Сколько сообщений показывать вокруг найденного. */
    private static final int AROUND_DEPTH = 10;

    /** Шире окно делать нельзя: контекст затопит. */
    private static final int MAX_AROUND = 25;

    /** Столько стикеров помещается в избранном и недавних. */

    private static final int CONTACT_LIMIT = 50;

    /** Длиннее превью в списке чатов не нужно. */
    private static final int PREVIEW_LENGTH = 80;

    /** Длинное голосовое никто не слушает. */
    private static final int MAX_VOICE_LENGTH = 400;

    /** Слова, выдающие ремарку вместо самой речи. */
    private static final List<String> NARRATION_WORDS =
            List.of("голосом", "тоном", "говорит ", "voice", "tone", "says ");

    private final Telegram telegram;
    private final TelegramActions actions;
    private final ChatScreen screen;
    private java.nio.file.Path gallery = java.nio.file.Path.of("data/gallery");
    private java.nio.file.Path voiceDir = java.nio.file.Path.of("data/voice_messages");
    private java.util.function.Supplier<String> speechPrompt =
            () -> "Exactly what you want to say in the voice message";
    private final AntiRepeat antiRepeat;
    private ru.rainedev.raine.vision.TelegramMedia media;
    private final StickerTools stickers;
    private ru.rainedev.raine.speech.VoiceGenerator voice;
    private ru.rainedev.raine.image.ImageGenerator camera;
    private PhotoOutcome photoOutcome = (chatId, ready, message) -> { };

    /** Сообщает, чем закончилась фоновая съёмка. */
    @FunctionalInterface
    public interface PhotoOutcome {
        void report(long chatId, boolean ready, String message);
    }
    private final TypingRhythm rhythm;
    private final java.util.random.RandomGenerator random = new java.util.Random();

    /** Вероятность одной опечатки на сообщение. */
    private static final double TYPO_PROBABILITY = 0.02;

    /** Насколько часто она сама замечает свою опечатку. */
    private static final double TYPO_NOTICED = 0.7;

    /** Насколько часто напоминать, что можно не только писать текстом. */
    private static final double LIVELIER_REMINDER = 0.02;

    private final ru.rainedev.raine.telegram.Lockdown lockdown;

    /** Повадки из настроек: объём истории, стикеры, напоминания. */
    private ru.rainedev.raine.config.Config.Behaviour behaviour =
            new ru.rainedev.raine.config.Config.Behaviour(2000, false, true, false, true, LIVELIER_REMINDER);

    public void behaviour(ru.rainedev.raine.config.Config.Behaviour behaviour) {
        this.behaviour = behaviour;
    }

    /**
     * Чаты, открытые за этот ход. Для Telegram «открытый чат» — это состояние:
     * пока он открыт, туда идут обновления и отметки прочтения. Оставлять их
     * открытыми навсегда — значит выглядеть человеком, у которого раскрыты
     * сразу все переписки.
     */
    private final java.util.Set<Long> opened = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Ход закончен: выходим из всех чатов, куда заходили. */
    public void closeOpened() {
        for (Long chatId : opened) {
            try {
                actions.stopTyping(chatId);
                actions.closeChat(chatId);
            } catch (RuntimeException e) {
                log.debug("Чат {} не закрылся: {}", chatId, e.getMessage());
            }
        }
        opened.clear();
    }

    /** Сейчас она пишет по своему порыву, а не отвечает на уведомление. */
    private java.util.function.BooleanSupplier actingOnImpulse = () -> false;

    public TelegramTools(Telegram telegram, TelegramActions actions, ChatScreen screen,
                         ru.rainedev.raine.telegram.Lockdown lockdown, AntiRepeat antiRepeat) {
        this.telegram = telegram;
        this.actions = actions;
        this.screen = screen;
        this.lockdown = lockdown;
        this.antiRepeat = antiRepeat;
        this.rhythm = new TypingRhythm(random);
        this.stickers = new StickerTools(telegram, actions);
        this.stickers.allowedTo(chatId -> allowed(telegram.chatCached(chatId)));
    }

    public StickerTools stickers() {
        return stickers;
    }

    public void actingOnImpulse(java.util.function.BooleanSupplier flag) {
        this.actingOnImpulse = flag;
    }

    /** Съёмка подключается отдельно: без ключа генерации инструмента просто не будет. */
    public void camera(ru.rainedev.raine.image.ImageGenerator camera, java.nio.file.Path gallery,
                       PhotoOutcome outcome) {
        this.camera = camera;
        this.gallery = gallery;
        this.photoOutcome = outcome;
    }

    /**
     * Снимок. Готовый файл кладётся в галерею, а отправляется отдельно —
     * так она может посмотреть на результат и переснять, если не понравился.
     */
    public Tool takePhoto(long chatId) {
        return Tool.named("take_photo")
                .describedAs("Takes a photo — a selfie, surroundings, anything. Returns a filename you can then "
                        + "send with send_telegram_message. Describe the vibe, not a complex composition.")
                .requiredString("photo_desc", "What the photo should show. Refer to yourself by name. "
                        + "Avoid complex composition — set the vibe instead. Example: \"Raine makes a playful "
                        + "selfie\". The camera only knows what you look like: to put someone else in the frame, "
                        + "name them and describe their appearance as specifically as you can. Example: "
                        + "\"Selfie of Raine with her sister: anime young female, gold eyes, white hair, "
                        + "white dress, black socks\".")
                .build(arguments -> {
                    String wish = arguments.path("photo_desc").asText("").strip();
                    if (wish.isEmpty()) {
                        throw new LowQualityException("Опиши, что должно быть на снимке.");
                    }
                    if (camera.isBusy()) {
                        return "You are already taking a photo. Wait until it is ready — you will be told. "
                                + "Do not start another one.";
                    }

                    boolean started = camera.takeInBackground(wish,
                            photo -> photoOutcome.report(chatId, true, photo.getFileName().toString()),
                            reason -> photoOutcome.report(chatId, false, reason));
                    if (!started) {
                        return "You are already taking a photo. Wait until it is ready.";
                    }

                    actions.uploadingPhoto(chatId);
                    log.info("Съёмка начата: {}", wish);
                    // управление возвращается сразу: очередь бывает занята десятками
                    // минут, и ждать её, замерев посреди разговора, нельзя
                    return "You started taking a photo. It takes a while — carry on talking, "
                            + "you will be notified when it is ready. Do not call take_photo again meanwhile.";
                });
    }

    /** Голос подключается отдельно: без ключа синтеза инструмента просто не будет. */
    public void voice(ru.rainedev.raine.speech.VoiceGenerator voice, java.nio.file.Path voiceDir,
                      java.util.function.Supplier<String> speechPrompt) {
        this.voice = voice;
        this.voiceDir = voiceDir;
        this.speechPrompt = speechPrompt;
    }

    /** Длительность нужна Telegram, иначе полоска голосового показывает ноль. */
    private static int secondsOf(java.nio.file.Path ogg) {
        try {
            // 24 кбит/с — тот битрейт, с которым мы кодируем
            return Math.max(1, (int) (java.nio.file.Files.size(ogg) * 8 / 24_000));
        } catch (java.io.IOException e) {
            return 1;
        }
    }

    /**
     * Голосовое сообщение. В описании инструмента важно подчеркнуть, что текст —
     * это ровно то, что будет произнесено: иначе модель пишет «Raine говорит
     * игриво...», и это зачитывается вслух.
     */
    public Tool recordAudio(long chatId) {
        return Tool.named("record_audio")
                .describedAs("Records a voice message and stores it. The text you provide is exactly what will be "
                        + "said out loud — no narration, no third person, no stage directions. Returns a filename; "
                        + "send it with send_telegram_message using audio_filename.")
                // описание параметра берётся из промпта: это часть характера,
                // а не техническая подпись — правится без пересборки
                .requiredString("audio_desc", speechPrompt.get())
                .build(arguments -> {
                    String text = arguments.path("audio_desc").asText("").strip();
                    if (text.isEmpty()) {
                        throw new LowQualityException("Нечего произносить — напиши, что сказать голосом.");
                    }
                    if (text.length() > MAX_VOICE_LENGTH) {
                        throw new LowQualityException(
                                "Слишком длинно для голосового (%d символов). Скажи короче.".formatted(text.length()));
                    }
                    // ремарка вроде «говорит игривым тоном» будет зачитана вслух
                    // ровно так, как написана
                    String lower = text.toLowerCase();
                    for (String narration : NARRATION_WORDS) {
                        if (lower.contains(narration)) {
                            throw new LowQualityException(
                                    "Не описывай, как ты это говоришь — напиши только сами слова. Вместо "
                                    + "«говорит игриво: привет» просто «привет».");
                        }
                    }
                    actions.recordingVoice(chatId);
                    var recorded = voice.speak(text);
                    // не отправляем сразу: запись сохраняется, а отправку она делает
                    // отдельным шагом — так же, как со снимком
                    log.info("Голосовое записано: {}", recorded.file().getFileName());
                    return "Voice message recorded.\n\nFilename: %s\n\nSend it with send_telegram_message "
                            .formatted(recorded.file().getFileName()) + "using audio_filename.";
                });
    }

    /** Зрение появляется позже клиента, поэтому подключается отдельно. */
    public void media(ru.rainedev.raine.vision.TelegramMedia media) {
        this.media = media;
        stickers.media(media);
    }

    /** Аватарка — по ней понятно, как выглядит собеседник. */
    public Tool chatPhoto(long chatId) {
        return Tool.simple("get_chat_photo", "Looks at the profile photo of this chat.", arguments -> {
            if (media == null) {
                return "You can't see photos right now.";
            }
            return telegram.chatPhoto(chatId)
                    .map(photo -> media.describeAvatar(photo))
                    .filter(described -> !described.isBlank())
                    // без этой оговорки она заговорит о фотографии так, будто её
                    // только что прислали, а это аватарка
                    .map(described -> described + "\nThis is their profile photo. If you mention it, make clear "
                            + "you are talking about their avatar.")
                    .orElse("This chat has no profile photo.");
        });
    }

    /**
     * Открывает чат: показывает переписку и подменяет набор инструментов на те,
     * что доступны внутри этого чата.
     *
     * @param toolbox набор, который будет дополнен после открытия
     */
    public Tool open(long chatId) {
        // в описании — имя чата: по нему видно, кто написал, ещё до открытия
        String title = "";
        try {
            title = telegram.chatCached(chatId).title;
        } catch (RuntimeException e) {
            log.debug("Название чата {} недоступно", chatId);
        }
        return Tool.named("open")
                .describedAs(title.isEmpty()
                        ? "Opens the chat from the notification and shows recent messages."
                        : "Open \"" + title + "\" chat. Use this if you'd like to reply or see messages.")
                .buildContextual((arguments, addTool) -> {
            TdApi.Chat chat = telegram.chat(chatId);
            if (!allowed(chat)) {
                // проверка и здесь, а не только на входящих: найдя человека поиском,
                // она открыла бы чат по идентификатору и написала бы в обход запрета
                log.info("Чат {} вне круга общения — не открываю", chatId);
                return "No such chat.";
            }
            actions.setOnline(true);   // зашла в Telegram — это видно собеседнику
            actions.openChat(chatId);
            opened.add(chatId);

            List<TdApi.Message> history = recentEnough(telegram.history(chatId, HISTORY_DEPTH));
            var view = new MessageFormatter.ChatView(telegram.myId(), chat.lastReadInboxMessageId);
            ChatScreen.Screen rendered = screen.render(chat, history, view);

            actions.markRead(chatId, history.stream().mapToLong(message -> message.id).toArray());

            // свои прошлые реплики — чтобы не повторяться и после перезапуска
            history.stream()
                    .filter(message -> message.senderId instanceof TdApi.MessageSenderUser sender
                            && sender.userId == telegram.myId())
                    .filter(message -> message.content instanceof TdApi.MessageText)
                    .forEach(message -> antiRepeat.remember(((TdApi.MessageText) message.content).text.text));

            // пустой чат — это человек, с которым она ещё не говорила ни разу.
            // Заговаривать с незнакомыми первой можно разрешить настройкой,
            // но по умолчанию это выключено: слишком легко написать не туда
            boolean stranger = history.isEmpty() && rendered.kind() == ChatKind.DM
                    && !lockdown.isOwner(chatId);
            if (stranger && !behaviour.writeToNewPeople()) {
                log.info("Чат {} пуст, а писать незнакомым не разрешено", chatId);
                return "This chat is empty — you have never talked to this person. "
                        + "You cannot write to them. Go back to a chat you already have.";
            }

            // в канале отвечать нечем — инструмента отправки там просто нет,
            // но реагировать и пересылать можно
            addTool.accept(searchMessages(chatId, view));
            addTool.accept(messagesAround(chatId, view));
            if (rendered.kind() != ChatKind.CHANNEL) {
                addTool.accept(send(chatId));
                if (voice != null && voice.isAvailable()) {
                    addTool.accept(recordAudio(chatId));
                }
                if (camera != null && camera.isAvailable()) {
                    addTool.accept(takePhoto(chatId));
                }
                if (behaviour.stickers()) {
                    addTool.accept(stickers.send(chatId));
                }
                addTool.accept(editMessage(chatId));
                addTool.accept(deleteMessage(chatId));
            }
            addTool.accept(chatPhoto(chatId));
            addTool.accept(react(chatId));
            if (behaviour.stickers()) {
                addTool.accept(stickers.save());
            }
            addTool.accept(forward(chatId));
            addTool.accept(blockChat(chatId));
            if (stranger) {
                // здесь ещё ничего не сказано: без опоры на разговор легко
                // написать что-то невпопад или не тому
                return rendered.text() + "\n\nThis chat is empty! Only proceed if you looked up a @username "
                        + "and it led you here. Write only what you have to say yourself. If you then go back "
                        + "to the original chat, remember you have already sent a message here.";
            }
            return rendered.text();
        });
    }

    /**
     * Отправка сообщения. Живой разговор состоит из коротких реплик,
     * поэтому простыня отклоняется с просьбой разбить её на части.
     * <p>
     * Цитирование по умолчанию не ставится: в переписке отвечают обычным
     * сообщением, а цитата нужна, только когда разговор ушёл вперёд и надо
     * показать, к чему относится реплика.
     */
    public Tool send(long chatId) {
        // счётчик живёт вместе с открытым чатом: в новом разговоре счёт заново
        int[] inARow = {0};
        long[] lastQuoted = {0};
        return Tool.named("send_telegram_message")
                .describedAs("Sends one short message to the currently opened chat. "
                        + "One call = one message. Split longer thoughts into several calls.")
                .optionalString("text", "Text of the message. May be left out if you attach a photo or a voice "
                        + "message.")
                .optionalString("allow_typos", "Optional. Set to \"false\" for a message where a typo would be "
                        + "out of place. Defaults to true.")
                .optionalString("photo_filename", "Optional. Filename returned by take_photo — the photo will be "
                        + "sent with this text as its caption.")
                .optionalString("audio_filename", "Optional. Filename returned by record_audio — the voice message "
                        + "will be sent instead of text.")
                .optionalString("reply_to_message_id",
                        "Optional. Quote a specific earlier message by its message_id. Leave empty for a normal "
                                + "reply — quoting every message looks unnatural in a live conversation.")
                .build(arguments -> {
                    String text = arguments.path("text").asText("").replace("\r", "").strip();
                    String photoName = arguments.path("photo_filename").asText("").strip();
                    String audioName = arguments.path("audio_filename").asText("").strip();

                    // «печатает…» держится всё время работы инструмента: пока считается
                    // вектор и грузится файл, собеседник иначе видит пустоту
                    try (TypingIndicator ignored = TypingIndicator.typing(actions, chatId)) {

                    if (text.isEmpty() && photoName.isEmpty() && audioName.isEmpty()) {
                        throw new LowQualityException("Пустое сообщение отправлять незачем — напиши, что хотела.");
                    }
                    if (!photoName.isEmpty() && !audioName.isEmpty()) {
                        return "You cannot attach a photo and a voice message at once.";
                    }
                    if (!audioName.isEmpty() && !text.isEmpty()) {
                        // у голосового не бывает подписи: либо голос, либо текст
                        return "A voice message cannot carry text. Send them separately.";
                    }
                    if (inARow[0] > MAX_IN_A_ROW) {
                        // предупреждения она перестаёт замечать — дальше только отказ
                        return "Too many messages in a row. Stop and let them answer.";
                    }
                    if (text.length() > MAX_MESSAGE_LENGTH) {
                        throw new LowQualityException(
                                "Слишком длинно для переписки (%d символов). Разбей на несколько коротких сообщений."
                                        .formatted(text.length()));
                    }
                    // чем длиннее реплика, тем выше шанс попросить разбить её на части.
                    // мягкий отказ работает лучше жёсткого предела: короткие сообщения
                    // становятся привычкой, а не исключением
                    // придираемся, только если это единственная отправка за шаг:
                    // когда она и так шлёт несколько реплик подряд, придирка ни к чему
                    if (!text.contains("```")
                            && ru.rainedev.raine.core.CurrentStep.countOf("send_telegram_message") <= 1
                            && random.nextDouble() < Math.clamp((text.length() - 50) / 200.0, 0, 1) * SPLIT_NUDGE) {
                        throw new LowQualityException("""
                                Разбей ответ на короткие отдельные сообщения. Например:
                                - ахахаха
                                - ты смешной
                                - научишь так же?""");
                    }
                    Long quotedCheck = quotedMessageId(arguments);
                    if (quotedCheck != null && quotedCheck == lastQuoted[0]) {
                        // на одно и то же сообщение дважды подряд не отвечают
                        quotedCheck = null;
                    } else if (quotedCheck != null) {
                        lastQuoted[0] = quotedCheck;
                    }
                    if (quotedCheck != null && telegram.message(chatId, quotedCheck).isEmpty()) {
                        // сообщение из другого чата: модель путает чаты, а цитата
                        // из чужой переписки — это уже утечка личного
                        log.warn("Попытка процитировать сообщение {} не из этого чата", quotedCheck);
                        return "That message is not from this chat. Quote only messages you see here, "
                                + "or leave reply_to_message_id empty.";
                    }

                    // снимок уходит с первой строкой в подписи, остальные строки —
                    // отдельными сообщениями следом, как обычная реплика
                    if (!photoName.isEmpty()) {
                        if (!isPlainFilename(photoName)) {
                            return "Filename must be a plain name, without slashes or dots leading elsewhere.";
                        }
                        java.nio.file.Path photo = gallery.resolve(photoName);
                        if (!java.nio.file.Files.exists(photo)) {
                            return "There is no such photo. Take one with take_photo first.";
                        }
                        List<String> lines = linesOf(text);
                        try (TypingIndicator uploading = TypingIndicator.uploadingPhoto(actions, chatId)) {
                            actions.sendPhoto(chatId, photo, lines.isEmpty() ? "" : lines.getFirst());
                        }
                        log.info("Фото отправлено в чат {}: {}", chatId, photoName);
                        for (String rest : lines.subList(Math.min(1, lines.size()), lines.size())) {
                            rhythm.sleepBefore(rest.length(), () -> actions.typing(chatId));
                            actions.sendMessage(chatId, rest.replace("—", "-"), null);
                        }
                        inARow[0]++;
                        return "Photo sent.";
                    }

                    if (!audioName.isEmpty()) {
                        if (!isPlainFilename(audioName)) {
                            return "Filename must be a plain name, without slashes or dots leading elsewhere.";
                        }
                        java.nio.file.Path recorded = voiceDir.resolve(audioName);
                        if (!java.nio.file.Files.exists(recorded)) {
                            return "There is no such voice message. Record one with record_audio first.";
                        }
                        try (TypingIndicator recording = TypingIndicator.recordingVoice(actions, chatId)) {
                            actions.sendVoice(chatId, recorded, secondsOf(recorded));
                        }
                        log.info("Голосовое отправлено в чат {}: {}", chatId, audioName);
                        return "Voice message sent.";
                    }

                    maybeSuggestSomethingLivelier();
                    antiRepeat.check(text);

                    boolean allowTypos = !"false".equalsIgnoreCase(arguments.path("allow_typos").asText("true"));
                    Long quoted = quotedCheck;

                    long lastSent = 0;
                    String lastText = text;

                    // переносы строк внутри одной реплики — это отдельные сообщения
                    for (String line : linesOf(text)) {
                        // модели пишут длинное тире, а с клавиатуры набирают дефис
                        String piece = line.replace("—", "-");

                        Typos.Slip slip = allowTypos
                                ? Typos.maybeAdd(piece, TYPO_PROBABILITY, random)
                                : new Typos.Slip(piece, "");

                        actions.typing(chatId);
                        rhythm.sleepBefore(slip.text().length(), () -> actions.typing(chatId));
                        // номер, который отдаётся модели, — настоящий: по временному
                        // не поправить и не удалить только что отправленное
                        long temporary = actions.sendMessage(chatId, slip.text(), quoted);
                        long sent = temporary == 0 ? 0 : telegram.confirmedId(temporary);
                        if (temporary != 0 && sent == 0) {
                            return "The message could not be sent. Something is wrong with the connection "
                                    + "or the account.";
                        }
                        quoted = null;   // цитата уместна только у первого сообщения
                        log.info("Отправлено в чат {}: {}", chatId, slip.text());

                        if (slip.happened()) {
                            fixTypo(chatId, sent, slip, piece);
                        }
                        lastSent = sent;
                        lastText = slip.text();
                    }
                    // считаем вызовы, а не строки: реплика, разбитая на три части,
                    // остаётся одной репликой
                    inARow[0]++;
                    return sendResult(inARow[0], lastSent, lastText);
                    }
                });
    }

    /** Пустые строки внутри реплики отправлять незачем. */
    private static List<String> linesOf(String text) {
        return java.util.Arrays.stream(text.split("\n"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /**
     * История чата ограничивается объёмом текста, а не числом сообщений.
     * Тридцать длинных постов из канала — это уже перебор контекста, а тридцать
     * «ок» — наоборот, слишком мелкий кусок разговора.
     *
     * @param history от старых к свежим
     */
    private List<TdApi.Message> recentEnough(List<TdApi.Message> history) {
        int budget = behaviour.chatHistoryLength();
        int taken = 0;
        int from = history.size();
        while (from > 0) {
            taken += MessageFormatter.contentOf(history.get(from - 1).content).length();
            from--;
            if (taken >= budget) {
                break;
            }
        }
        return history.subList(from, history.size());
    }

    /** Реакция — способ откликнуться, не заводя разговор. */
    public Tool react(long chatId) {
        return Tool.named("react_with_emoji")
                .describedAs("Reacts to a message with an emoji. A subtle way to acknowledge something without "
                        + "sending a full reply. Allowed: " + String.join(" ", Emoji.ALLOWED))
                .requiredInteger("message_id", "Id of the message to react to")
                .requiredString("emoji", "One emoji from the allowed list")
                .build(arguments -> {
                    long messageId = Numbers.longAt(arguments, "message_id", 0);
                    String wanted = Emoji.normalize(arguments.path("emoji").asText(""));
                    if (wanted.isEmpty()) {
                        log.info("Реакция не поставлена: эмодзи не разобрано ({})",
                                arguments.path("emoji").asText(""));
                        return "Provide an emoji to react with.";
                    }

                    // берём ту форму эмодзи, которую называет сам Telegram: у одних
                    // реакций есть невидимый вариационный селектор, у других нет,
                    // и набор отличается от чата к чату
                    List<String> available = telegram.availableReactions(chatId, messageId);
                    String exact = available.stream()
                            .filter(candidate -> Emoji.normalize(candidate).equals(wanted))
                            .findFirst()
                            .orElse(null);

                    if (exact == null) {
                        if (available.isEmpty()) {
                            // важно сказать, что это не поломка: иначе она решит,
                            // что инструмент сломан, и перестанет им пользоваться
                            log.info("В чате {} реакции отключены — сообщение {} без реакции", chatId, messageId);
                            return "Reactions are disabled for this message — you can't react here. "
                                    + "Just skip it, this is not an error.";
                        }
                        // без этой строки «реакции иногда не работают» не расследовать:
                        // отказ уходит модели, а в журнале не остаётся ничего
                        log.info("Реакция {} недоступна для сообщения {}; здесь можно: {}",
                                wanted, messageId, String.join(" ", available));
                        return "The reaction " + wanted + " isn't available for this message. Allowed here: "
                                + String.join(" ", available) + ". Pick one of those, or just skip reacting.";
                    }

                    try {
                        actions.react(chatId, messageId, exact);
                    } catch (RuntimeException e) {
                        // отдельные сообщения не принимают реакции по своим причинам.
                        // Важно, чтобы она не решила, что инструмент сломан вообще
                        log.info("Реакция на сообщение {} не прошла: {}", messageId, e.getMessage());
                        return "Couldn't react to this message. It happens with some messages — just skip it, "
                                + "this is not a real error.";
                    }
                    log.info("Реакция {} на сообщение {}", exact, messageId);
                    return "Reaction " + exact + " added.";
                });
    }

    /** Список чатов — как экран мессенджера: видно, где есть непрочитанное. */
    public Tool chatList() {
        return Tool.simple("get_telegram_chats", "Shows your Telegram chats with unread counts.", arguments -> {
            // от старых к свежим: список разбирается сверху вниз, и наверху
            // должно оказаться то, до чего иначе не дойдут руки
            List<TdApi.Chat> chats = new java.util.ArrayList<>(telegram.chats(CHAT_LIST_DEPTH));
            java.util.Collections.reverse(chats);

            // чат, с которым нельзя заговорить, в списке только мешает
            chats.removeIf(chat -> !allowed(chat));

            if (actingOnImpulse.getAsBoolean()) {
                // весь смысл порыва — вернуться к заглохшим разговорам. Про те,
                // где есть непрочитанное, она и так узнает из уведомлений
                chats.removeIf(chat -> chat.unreadCount > 0);
            }

            StringBuilder out = new StringBuilder(
                    "You are looking at your Telegram main screen. You see these chats:\n<chats>\n");
            for (TdApi.Chat chat : chats) {
                out.append("<chat id=\"").append(chat.id).append("\" title=\"").append(chat.title)
                        .append("\" kind=\"").append(kindOf(chat)).append('"');
                if (chat.unreadCount > 0) {
                    out.append(" unread_count=\"").append(chat.unreadCount).append('"');
                }
                // по одному названию не выбрать, кому написать: превью показывает,
                // о чём там речь и как давно
                String preview = previewOf(chat);
                if (!preview.isEmpty()) {
                    out.append('>').append(preview).append("</chat>\n");
                } else {
                    out.append("/>\n");
                }
            }
            return out.append("</chats>").toString();
        });
    }

    /** Поиск человека или чата — так завязывается разговор с тем, кому ещё не писала. */
    public Tool searchChats() {
        return Tool.named("search_chats")
                .describedAs("Finds a person or chat by name or @username. Searches your contacts, your existing "
                        + "chats and public channels at once. Use it before starting a conversation with someone "
                        + "new — if the chat already exists, continue there instead.")
                .requiredString("query", "Person's name, chat title or @username")
                .build(arguments -> {
                    String query = arguments.path("query").asText("").strip();
                    if (query.isEmpty()) {
                        return "Provide a name or @username to search for.";
                    }
                    Telegram.Found found = telegram.searchChats(query, SEARCH_LIMIT);
                    if (found.isEmpty()) {
                        return "Nothing found for: " + query;
                    }
                    // разделено нарочно: со своими разговор продолжают с того места,
                    // где он встал, а к чужим приходят с нуля — это разные ситуации
                    StringBuilder out = new StringBuilder();
                    appendChats(out, "chats_you_are_already_in", found.mine());
                    appendChats(out, "chats_that_do_not_know_you", found.elsewhere());
                    return out.toString();
                });
    }

    private static void appendChats(StringBuilder out, String tag, List<TdApi.Chat> chats) {
        if (chats.isEmpty()) {
            return;
        }
        out.append('<').append(tag).append(">\n");
        for (TdApi.Chat chat : chats) {
            out.append("<chat id=\"").append(chat.id)
                    .append("\" title=\"").append(chat.title)
                    .append("\" kind=\"").append(kindOf(chat))
                    .append("\"/>\n");
        }
        out.append("</").append(tag).append(">\n");
    }

    /** Тип важен: с человеком можно заговорить, а в канал не напишешь. */
    private static String kindOf(TdApi.Chat chat) {
        return switch (ChatKind.of(chat)) {
            case DM -> "person";
            case GROUP -> "group";
            case CHANNEL -> "channel";
        };
    }

    /** Кто и что написал последним, и когда — как в списке чатов обычного клиента. */
    private String previewOf(TdApi.Chat chat) {
        if (chat.lastMessage == null) {
            return "";
        }
        long senderId = chat.lastMessage.senderId instanceof TdApi.MessageSenderUser user ? user.userId : 0;
        String preview = (telegram.nameOf(senderId) + ": "
                + ru.rainedev.raine.phone.MessageFormatter.contentOf(chat.lastMessage.content)).replace("\n", " ");
        if (preview.length() > PREVIEW_LENGTH) {
            preview = preview.substring(0, 30) + "..." + preview.substring(preview.length() - 30);
        }
        return preview + " (" + ru.rainedev.raine.phone.TimeAgo.of(chat.lastMessage.date) + ")";
    }

    /** Свои контакты — список людей, которых она знает по именам. */
    public Tool contactList() {
        return Tool.simple("get_contacts", "Shows people saved in your contacts.", arguments -> {
            List<TdApi.User> contacts = telegram.contacts(CONTACT_LIMIT);
            if (contacts.isEmpty()) {
                return "Your contact list is empty.";
            }
            StringBuilder out = new StringBuilder("<contacts>\n");
            for (TdApi.User user : contacts) {
                out.append("<contact user_id=\"").append(user.id).append("\" name=\"")
                        .append((user.firstName + " " + user.lastName).strip()).append("\"/>\n");
            }
            return out.append("</contacts>").toString();
        });
    }

    /**
     * Сохранение в контакты. Человек, записанный по имени, потом находится
     * поиском — иначе он теряется среди чатов, как только переписка уходит вниз.
     */
    public Tool saveContact() {
        return Tool.named("save_contact")
                .describedAs("Saves a person to your contacts so you can find them by name later. Use it when "
                        + "you got to know someone and want to keep them.")
                .requiredInteger("user_id", "Id of the person")
                .requiredString("first_name", "How you want to call them in your contacts")
                .optionalString("last_name", "Optional last name")
                .build(arguments -> {
                    long userId = Numbers.longAt(arguments, "user_id", 0);
                    String firstName = arguments.path("first_name").asText("").strip();
                    if (userId == 0 || firstName.isEmpty()) {
                        return "Both user_id and first_name are required.";
                    }
                    actions.saveContact(userId, firstName, arguments.path("last_name").asText("").strip());
                    log.info("Контакт сохранён: {} ({})", firstName, userId);
                    return "Contact saved.";
                });
    }

    /** Открытие чата по идентификатору — так она пишет первой. */
    public Tool openById() {
        return Tool.named("open_chat_by_id")
                .describedAs("Opens a chat by its id. Use get_telegram_chats or search_chats to find the id. "
                        + "Opening another chat replaces the previously opened one.")
                .requiredInteger("chat_id", "Id of the chat to open")
                .buildContextual((arguments, addTool) -> {
                    // два разных чата за один шаг — верный признак путаницы,
                    // и следом за этим сообщение уходит не тому
                    if (ru.rainedev.raine.core.CurrentStep.countOf("open_chat_by_id") > 1) {
                        return "You can only open one chat at a time. Open one, finish there, then move on.";
                    }
                    return open(Numbers.longAt(arguments, "chat_id", 0)).handler().call(arguments, addTool);
                });
    }

    /** Пересылка — поделиться чужим постом, не пересказывая его. */
    public Tool forward(long fromChatId) {
        return Tool.named("forward_message")
                .describedAs("Forwards a message from this chat to another one — to a friend, a group, or to your "
                        + "saved messages. Use it when you find something worth sharing. You can add a comment; "
                        + "it goes as a separate message after the forward, the way people do it.")
                .requiredString("message_id", "Id of the message to forward, or several ids separated by commas")
                .requiredInteger("to_chat_id", "Id of the destination chat. Use your own chat id to save it "
                        + "for yourself.")
                .optionalString("comment", "Optional. Your reaction, sent right after the forwarded message.")
                .build(arguments -> {
                    long toChatId = Numbers.longAt(arguments, "to_chat_id", 0);
                    List<Long> ids = new java.util.ArrayList<>();
                    for (String part : arguments.path("message_id").asText("").split("[,\\s]+")) {
                        try {
                            ids.add(Long.parseLong(part.strip()));
                        } catch (NumberFormatException ignored) {
                            // мусор среди идентификаторов пропускаем
                        }
                    }
                    if (ids.isEmpty()) {
                        return "Provide the id of the message to forward.";
                    }
                    // получатель тоже должен быть в круге общения: иначе переписку
                    // можно переслать кому угодно в обход ограничения
                    if (!allowed(telegram.chatCached(toChatId))) {
                        log.info("Пересылка в чат {} вне круга общения отклонена", toChatId);
                        return "You cannot forward anything to that chat.";
                    }
                    // проверяем до отправки: переслать половину и упасть хуже,
                    // чем не пересылать вовсе
                    for (long id : ids) {
                        if (telegram.message(fromChatId, id).isEmpty()) {
                            return "Message " + id + " is not in this chat.";
                        }
                    }
                    actions.forward(toChatId, fromChatId, ids.stream().mapToLong(Long::longValue).toArray());
                    log.info("Переслано сообщений: {} в чат {}", ids.size(), toChatId);

                    String comment = arguments.path("comment").asText("").strip();
                    if (!comment.isEmpty()) {
                        rhythm.sleepBefore(comment.length(), () -> actions.typing(toChatId));
                        actions.sendMessage(toChatId, comment.replace("—", "-"), null);
                        log.info("Комментарий к пересылке: {}", comment);
                    }
                    return "Message forwarded.";
                });
    }

    /** Правка уже отправленного — вместо второго сообщения «*опечатка». */
    public Tool editMessage(long chatId) {
        return Tool.named("edit_message_text")
                .describedAs("Edits the text of your own message you have already sent. Use this to fix a typo "
                        + "or correct a mistake you notice in your own message.")
                .requiredInteger("message_id", "Id of your message")
                .requiredString("text", "New text")
                .build(arguments -> {
                    long messageId = Numbers.longAt(arguments, "message_id", 0);
                    String text = arguments.path("text").asText("").strip();
                    if (text.isEmpty()) {
                        return "New text must not be empty.";
                    }
                    // сверяемся с Telegram: у него свой идентификатор, и правка
                    // по выдуманному номеру молча уходила бы в никуда
                    java.util.Optional<TdApi.Message> existing = telegram.message(chatId, messageId);
                    if (existing.isEmpty()) {
                        return "There is no such message in this chat. Check the message_id.";
                    }
                    long realId = existing.get().id;
                    // у подписи к снимку правится не текст, а именно подпись
                    boolean isMedia = !(existing.get().content instanceof TdApi.MessageText);
                    actions.editText(chatId, realId, text, isMedia);
                    telegram.forgetMessage(chatId, realId);   // текст сменился
                    log.info("Сообщение {} изменено", realId);
                    return "Message edited.";
                });
    }

    /** Удаление своего сообщения — передумала. */
    public Tool deleteMessage(long chatId) {
        return Tool.named("remove_message")
                .describedAs("Deletes your own message for everyone. Use it when you sent something to the wrong "
                        + "chat or want to take it back.")
                .requiredString("message_id", "Id of your message, or several ids separated by commas")
                .build(arguments -> {
                    // модель порой хочет убрать сразу несколько своих реплик
                    List<Long> ids = new java.util.ArrayList<>();
                    for (String part : arguments.path("message_id").asText("").split("[,\\s]+")) {
                        try {
                            ids.add(Long.parseLong(part.strip()));
                        } catch (NumberFormatException ignored) {
                            // мусор среди идентификаторов пропускаем
                        }
                    }
                    if (ids.isEmpty()) {
                        return "Provide the id of the message to delete.";
                    }
                    // проверяем до удаления: сообщения из другого чата удалять нечем,
                    // а промах по номеру иначе выглядел бы как успех
                    for (long id : ids) {
                        if (telegram.message(chatId, id).isEmpty()) {
                            return "Message " + id + " is not in this chat.";
                        }
                    }
                    ids.forEach(id -> {
                        actions.deleteMessage(chatId, id);
                        telegram.forgetMessage(chatId, id);
                    });
                    log.info("Удалено сообщений: {}", ids.size());
                    return "Deleted " + ids.size() + " message(s).";
                });
    }

    /** Крайняя мера против того, кто не понимает отказа. */
    public Tool blockChat(long chatId) {
        return Tool.simple("remove_and_ban_chat",
                """
                Removes this chat from your list; for a private chat it also stops that person from writing to you.
                Use this if:
                - you dislike this chat and this person;
                - they are consistently rude to you (consult #ask before deciding);
                - they are needy: asking you to roleplay, to write code, to solve their tasks.
                You are not an assistant. It is fine to close a door.""",
                arguments -> {
                    if (lockdown.isOwner(chatId)) {
                        // единственный человек, которого нельзя закрыть
                        return "This is your person. You cannot block this chat.";
                    }
                    actions.blockAndLeave(chatId);
                    log.info("Чат {} заблокирован", chatId);
                    return "Chat blocked.";
                });
    }

    
    
    
    /** Поиск по переписке — «когда мы это обсуждали?». */
    public Tool searchMessages(long chatId, MessageFormatter.ChatView view) {
        return Tool.named("search_messages")
                .describedAs("Searches messages by their verbatim text, like the search in a Telegram client. "
                        + "Unlike #ask this does not use your diary — it finds an exact phrase, link or quote as "
                        + "Telegram stored it. Set everywhere to true to search across all your chats.")
                .requiredString("query", "Verbatim text (or part of it) to search for")
                .optionalString("everywhere", "Set to \"true\" to search across all your chats, not just this one.")
                .optionalString("sender_id", "Optional. Only messages from this person, by user_id.")
                .optionalString("from_days_ago", "Optional. Only messages sent at most this many days ago, "
                        + "e.g. 7 for the last week.")
                .optionalString("to_days_ago", "Optional. Only messages sent at least this many days ago, "
                        + "e.g. 1 for up until yesterday.")
                .optionalString("limit", "Optional. How many results to show. Default 20, at most 50.")
                .build(arguments -> {
                    String query = arguments.path("query").asText("").strip();
                    if (query.isEmpty()) {
                        return "Provide words to search for.";
                    }
                    boolean everywhere = "true".equalsIgnoreCase(arguments.path("everywhere").asText("false"));
                    long senderId = Numbers.longAt(arguments, "sender_id", 0);
                    long fromDaysAgo = Numbers.longAt(arguments, "from_days_ago", 0);
                    long toDaysAgo = Numbers.longAt(arguments, "to_days_ago", 0);

                    int limit = (int) Math.clamp(Numbers.longAt(arguments, "limit", SEARCH_LIMIT), 1, MAX_SEARCH_LIMIT);

                    Telegram.FoundMessages found;
                    if (everywhere) {
                        // поиск по всем чатам не должен выдавать переписку с теми,
                        // с кем ей вообще не положено общаться
                        Telegram.FoundMessages all = telegram.searchAllMessages(query, limit, fromDaysAgo, toDaysAgo);
                        found = new Telegram.FoundMessages(all.messages().stream()
                                .filter(message -> allowed(telegram.chatCached(message.chatId)))
                                .filter(message -> senderId == 0 || senderOf(message) == senderId)
                                .toList(), all.total());
                    } else {
                        found = telegram.searchMessages(chatId, query, senderId, limit, fromDaysAgo, toDaysAgo);
                    }
                    if (found.isEmpty()) {
                        return "Nothing found for: " + query;
                    }
                    // видно, показали ли всё или только верхушку: от этого зависит,
                    // стоит ли уточнять запрос
                    StringBuilder out = new StringBuilder("<found total_count=\"")
                            .append(found.total()).append("\" returned_count=\"")
                            .append(found.messages().size()).append("\">\n");
                    for (TdApi.Message message : found.messages()) {
                        // при поиске по всем чатам иначе непонятно, где это было сказано
                        if (everywhere) {
                            out.append("<in chat_id=\"").append(message.chatId).append("\" title=\"")
                                    .append(telegram.chatCached(message.chatId).title).append("\">\n");
                        }
                        out.append(screen.formatter().format(message, view));
                        if (everywhere) {
                            out.append("</in>\n");
                        }
                    }
                    return out.append("</found>\n").toString();
                });
    }

    /** Контекст вокруг найденного: одна реплика без соседних часто непонятна. */
    public Tool messagesAround(long chatId, MessageFormatter.ChatView view) {
        return Tool.named("view_messages_around")
                .describedAs("Shows messages before and after a given message_id, so you can see what was being "
                        + "discussed. Use it after search_messages found something but you need the context around "
                        + "it. The message itself is included and marked.")
                .requiredInteger("message_id", "Id of the message to look around")
                .optionalString("chat_id", "Optional. Chat to look in. Defaults to the chat you have open.")
                .optionalString("before", "Optional. How many older messages to include. Default 10.")
                .optionalString("after", "Optional. How many newer messages to include. Default 10.")
                .build(arguments -> {
                    long target = Numbers.longAt(arguments, "message_id", 0);
                    // поиск по всем чатам находит сообщения где угодно — смотреть
                    // вокруг них тоже надо уметь, не открывая чат целиком
                    long where = Numbers.longAt(arguments, "chat_id", chatId);
                    if (where != chatId && !allowed(telegram.chatCached(where))) {
                        log.info("Просмотр чата {} вне круга общения отклонён", where);
                        return "No such chat.";
                    }
                    int before = window(arguments, "before");
                    int after = window(arguments, "after");
                    List<TdApi.Message> around = telegram.messagesAround(where, target, before, after);
                    if (around.isEmpty()) {
                        return "Nothing around that message.";
                    }
                    StringBuilder out = new StringBuilder("<messages_around message_id=\"")
                            .append(target).append("\" chat_id=\"").append(where).append("\">\n");
                    for (TdApi.Message message : around) {
                        out.append(screen.formatter().format(message, view, message.id == target));
                    }
                    return out.append("</messages_around>\n").toString();
                });
    }

    private static long senderOf(TdApi.Message message) {
        return message.senderId instanceof TdApi.MessageSenderUser user ? user.userId : 0;
    }

    /** Окно ограничено: слишком широкое затопит контекст и вытеснит сам разговор. */
    private static int window(com.fasterxml.jackson.databind.JsonNode arguments, String name) {
        int value = arguments.path(name).asInt(AROUND_DEPTH);
        return Math.clamp(value, 0, MAX_AROUND);
    }

    private String render(List<TdApi.Message> messages, MessageFormatter.ChatView view) {
        StringBuilder out = new StringBuilder();
        for (TdApi.Message message : messages) {
            out.append(screen.formatter().format(message, view));
        }
        return out.toString();
    }

    private boolean allowed(TdApi.Chat chat) {
        long userId = chat.type instanceof TdApi.ChatTypePrivate priv ? priv.userId : 0;
        return lockdown.allows(chat, userId != 0 && telegram.isContact(userId));
    }

    /**
     * Человек замечает свою опечатку. Дальше он либо правит сообщение, либо
     * дописывает следом «*слово» — делают и так, и так, поэтому выбираем случайно.
     */
    private void fixTypo(long chatId, long sentMessageId, Typos.Slip slip, String correct) {
        // иногда опечатка так и остаётся незамеченной — это тоже по-человечески
        if (random.nextDouble() >= TYPO_NOTICED) {
            return;
        }
        try {
            Thread.sleep(java.time.Duration.ofMillis(1500 + random.nextInt(3500)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (sentMessageId != 0 && random.nextBoolean()) {
            actions.editText(chatId, sentMessageId, correct, false);
            log.info("Опечатка исправлена правкой сообщения");
            return;
        }
        actions.typing(chatId);
        actions.sendMessage(chatId, "*" + slip.original(), null);
        log.info("Опечатка исправлена следом: *{}", slip.original());
    }

    /**
     * Ответ на отправку. Идентификатор нужен, чтобы она могла поправить своё же
     * сообщение — без него исправить опечатку нечем.
     */
    private static String sendResult(int inARow, long messageId, String text) {
        String result = "Message sent; message_id=%d, text=\"%s\".".formatted(messageId, text);
        if (inARow > 5) {
            // заговорить человека — обычная беда: сообщения идут одно за другим,
            // а ответить он не успевает
            return result + "\n\nWarning: you have sent " + inARow
                    + " messages in a row! Give your participant space to breathe!";
        }
        if (inARow < 3) {
            // живая реплика редко бывает одна: «привет» — «как ты?»
            return result + "\n\nYou may add a follow-up #send_telegram_message.";
        }
        return result;
    }

    /**
     * Изредка напоминает, что переписка не обязана состоять из одного текста.
     * Сообщение при этом не уходит: она отправит вместо него что-то живее.
     */
    private void maybeSuggestSomethingLivelier() {
        if (random.nextDouble() >= behaviour.toolReminder()) {
            return;
        }
        List<String> options = new java.util.ArrayList<>();
        if (behaviour.stickers()) {
            options.add("- отправить стикер (#sticker_send)");
        }
        if (camera != null && camera.isAvailable()) {
            options.add("- сделать снимок (#take_photo) или отправить старый из галереи");
        }
        if (voice != null && voice.isAvailable()) {
            options.add("- записать голосовое (#record_audio)");
        }
        String suggestion = options.get(random.nextInt(options.size()));
        log.info("Напоминаю разнообразить общение: {}", suggestion);
        throw new LowQualityException("Один сплошной текст — скучно. Попробуй вместо этого:\n" + suggestion);
    }

    /**
     * Имя файла приходит от модели, а значит может прийти что угодно. Без этой
     * проверки «../../» в имени вывел бы за пределы галереи — к любому файлу
     * на диске.
     */
    private static boolean isPlainFilename(String name) {
        return !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.contains("..");
    }

    /** Модель может прислать id строкой, числом или не прислать вовсе. */
    private static Long quotedMessageId(com.fasterxml.jackson.databind.JsonNode arguments) {
        return Numbers.longAt(arguments, "reply_to_message_id").orElse(null);
    }
}
