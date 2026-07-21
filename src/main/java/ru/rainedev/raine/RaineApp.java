package ru.rainedev.raine;

import it.tdlight.client.SimpleAuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.jni.TdApi;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.core.Notification;
import ru.rainedev.raine.core.NotificationLoop;
import ru.rainedev.raine.core.Rest;
import ru.rainedev.raine.core.Spontaneity;
import ru.rainedev.raine.core.Toolbox;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.OpenAiCompatibleClient;
import ru.rainedev.raine.memory.Diary;
import ru.rainedev.raine.memory.DiaryMemory;
import ru.rainedev.raine.memory.DiaryWriter;
import ru.rainedev.raine.memory.Recall;
import ru.rainedev.raine.memory.WorkingMemory;
import ru.rainedev.raine.phone.ChatKind;
import ru.rainedev.raine.phone.ChatScreen;
import ru.rainedev.raine.phone.MediaDescriber;
import ru.rainedev.raine.phone.MessageFormatter;
import ru.rainedev.raine.phone.Notifications;
import ru.rainedev.raine.prompt.Prompts;
import ru.rainedev.raine.telegram.Lockdown;
import ru.rainedev.raine.telegram.Telegram;
import ru.rainedev.raine.telegram.TelegramActions;
import ru.rainedev.raine.tools.AntiRepeat;
import ru.rainedev.raine.tools.TelegramTools;
import ru.rainedev.raine.tools.WebSearch;
import ru.rainedev.raine.vision.TelegramMedia;
import ru.rainedev.raine.vision.Vision;

/** Связывает Telegram, промпты и цикл обработки в одно живое целое. */
public final class RaineApp implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RaineApp.class);

    private final Config config;
    private final SimpleTelegramClient client;

    private Telegram telegram;
    private TelegramTools tools;
    private NotificationLoop loop;
    private Thread brain;
    private Rest rest;
    private TelegramMedia telegramMedia;
    private Vision vision;
    private Lockdown lockdown;
    private TelegramActions actions;
    private ru.rainedev.raine.core.Surroundings around;
    private final java.util.Random random = new java.util.Random();

    /** Столько чатов просматривается при запуске в поисках непрочитанного. */
    private static final int CATCH_UP_CHATS = 50;

    /** Насколько часто сообщение из заглушённого чата остаётся без внимания. */
    private static final double MUTED_IGNORE_CHANCE = 0.8;
    private Spontaneity spontaneity;

    public RaineApp(SimpleTelegramClientBuilder builder,
                    SimpleAuthenticationSupplier<?> authentication,
                    Config config) {
        this.config = config;
        // после заморозки аккаунта продолжать нельзя: каждая следующая попытка
        // только копит отказы
        ru.rainedev.raine.core.Fatal.handler(error -> {
            log.error("Останавливаюсь: учётная запись недоступна");
            stopEverything();
        });
        builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::onAuthorizationState);
        builder.addUpdateHandler(TdApi.UpdateConnectionState.class, this::onConnectionState);
        builder.addUpdateHandler(TdApi.UpdateNewMessage.class, this::onNewMessage);
        // Telegram подтверждает отправку отдельным событием и только там называет
        // настоящий номер сообщения
        builder.addUpdateHandler(TdApi.UpdateMessageSendSucceeded.class,
                update -> telegram.onSendSucceeded(update.oldMessageId, update.message.id));
        builder.addUpdateHandler(TdApi.UpdateMessageSendFailed.class,
                update -> telegram.onSendFailed(update.oldMessageId,
                        update.error == null ? "причина неизвестна" : update.error.message));
        this.client = builder.build(authentication);
    }

    public SimpleTelegramClient client() {
        return client;
    }

    /** Поднимает всё остальное. Вызывается после того, как клиент авторизовался. */
    public void start() {
        telegram = new Telegram(client, config.character().name());
        lockdown = new Lockdown(Lockdown.Mode.of(config.lockdown()), config.ownerId(),
                config.lockdownAllowChannels());
        log.info("Круг общения: {}", switch (lockdown.mode()) {
            case NONE -> "все";
            case CONTACTS_ONLY -> "только контакты";
            case OWNER_ONLY -> "только владелец";
        });
        // без этого поиск по своим чатам возвращает пустоту: TDLib ищет
        // только по тому, что уже загружено с сервера
        telegram.loadChats(200);

        ru.rainedev.raine.llm.Transcript.enabled(config.llmTranscript());
        Prompts prompts = new Prompts(config.promptsDir(), config.character());
        LlmClient llm = new OpenAiCompatibleClient(config.llm(), config.embeddingModel());

        // зрение подключается к разметке: описание встаёт прямо под вложением
        MediaDescriber media = MediaDescriber.NONE;
        if (config.vision().enabled()) {
            vision = new Vision(llm, prompts, config.vision().cacheDir(),
                    config.vision().model(), config.vision().cheapModel(), config.character().name());
            telegramMedia = new TelegramMedia(telegram, vision, () -> loop == null ? List.of() : loop.context());
            media = telegramMedia;
        } else {
            log.info("Зрение выключено: вложения видны только по типу");
        }

        MessageFormatter formatter = new MessageFormatter(
                telegram::nameOf,
                (chatId, messageId) -> telegram.message(chatId, messageId),
                media);

        actions = config.readOnly()
                ? new TelegramActions.ReadOnly()
                : new TelegramActions.Live(client, telegram);
        if (config.readOnly()) {
            log.warn("Режим только чтения: Raine думает, но ничего не отправляет и не открывает чаты");
        }

        tools = new TelegramTools(telegram, actions, new ChatScreen(formatter, prompts), lockdown,
                new AntiRepeat(llm, prompts.lazy("anti_repeat.md"), config.repeat()));
        tools.behaviour(config.behaviour());
        if (telegramMedia != null) {
            tools.media(telegramMedia);
        }

        var horde = new ru.rainedev.raine.image.HordeClient(
                config.camera().baseUrl(), config.camera().apiKey(), config.camera().models());
        if (config.camera().enabled() && horde.isAvailable()) {
            tools.camera(new ru.rainedev.raine.image.ImageGenerator(horde, llm, prompts,
                    config.camera().gallery(), config.character().name(),
                    config.vision().model(), config.camera().maxTrials()),
                    config.camera().gallery(), this::onPhotoOutcome);
        } else {
            log.info("Генерация снимков не настроена: сфотографироваться нечем");
        }

        var voiceGenerator = new ru.rainedev.raine.speech.VoiceGenerator(config.voice(), config.voice().directory());
        if (voiceGenerator.isAvailable()) {
            tools.voice(voiceGenerator, config.voice().directory(), prompts.lazy("record_audio_speech.md"));
        } else {
            log.info("Синтез речи не настроен: голосовые записывать нечем");
        }

        Diary diary = new Diary(config.diaryDir());
        diary.embedder(llm::embedding);

        // ask доступен всегда: заглянуть в память можно в любой момент хода
        Toolbox always = new Toolbox();
        WebSearch web = new WebSearch(config.webSearchUrl(), config.webSearchKey());
        Recall recall = new Recall(diary, llm, prompts.lazy("character_base.md"), web);
        always.add(recall.asTool(this::currentSituation));
        if (config.behaviour().stickers()) {
            always.add(tools.stickers().list());
        }
        always.add(tools.chatList());
        always.add(tools.contactList());
        always.add(tools.saveContact());
        always.add(tools.searchChats());
        always.add(tools.openById());

        WorkingMemory workingMemory = new WorkingMemory(config.workingMemoryFile(), llm, config.character().name());
        around = new ru.rainedev.raine.core.Surroundings(
                config.around().enabled(), config.around().latitude(), config.around().longitude());

        loop = new NotificationLoop(
                llm,
                () -> prompts.system(around.asPromptSuffix() + workingMemory.asPromptSuffix()),
                always, config.contextTokenLimit());
        loop.memory(new DiaryMemory(diary, llm, config.diaryInjectionMaxLength(), config.diaryMinRelatedness()));
        DiaryWriter writer = new DiaryWriter(
                diary, llm, prompts.lazy("diary_save.md"), config.diaryPlagiarismThreshold());
        loop.onContextOverflow(() -> {
            // разговор не выбрасываем, а переносим в долгую память
            log.info("Контекст переполнен — переношу разговор в дневник");
            try {
                // сначала средняя память: ей нужен ещё не урезанный разговор
                workingMemory.update(prompts.system(""), loop.context());
                writer.save(prompts.system(""), loop.context());
            } catch (RuntimeException e) {
                log.error("Не удалось сохранить память — разговор будет потерян", e);
            }
            loop.clearContext();
            diary.reload();
        });

        // ход закончен — выходим из чатов, в которые заходили
        loop.onNotificationDone(done -> tools.closeOpened());

        rest = new Rest(random);
        rest.dayNaps(config.behaviour().dayNaps());
        loop.rest(rest);

        if (config.night().enabled()) {
            var night = new ru.rainedev.raine.core.NightSleep(
                    config.night().bedtimeFrom(), config.night().bedtimeTo(),
                    config.night().wakeFrom(), config.night().wakeTo(),
                    java.time.Clock.systemDefaultZone(), random);
            rest.night(night);
            log.info("Сегодня ложится около {}, встаёт около {}",
                    night.bedtime().toLocalTime().withSecond(0), night.wakeTime().toLocalTime().withSecond(0));
        }

        if (config.sleepConsolidation()) {
            var consolidation = new ru.rainedev.raine.memory.SleepConsolidation(
                    diary, llm, prompts.lazy("sleep_consolidator.md"),
                    config.diaryDir().resolve("archive"), config.diaryConsolidationBudget(), random);
            rest.duringRest(consolidation::run);
            log.info("Пересмотр памяти во сне включён");
        }

        spontaneity = new Spontaneity(loop, diary, always, random);
        tools.actingOnImpulse(spontaneity::isActing);
        spontaneity.start();

        // «в сети» держится только пока она занята перепиской: остальное время
        // и весь сон её видно офлайн, как любого человека
        loop.onIdle(() -> actions.setOnline(false));
        rest.onStateChange(resting -> actions.setOnline(false));

        brain = Thread.ofVirtual().name("raine-brain").start(loop::run);
        Thread.ofVirtual().name("raine-catchup").start(this::catchUpOnUnread);
        log.info("Raine запущена от имени {} (id {})", config.character().name(), telegram.myId());
    }

    /**
     * Снимок готовится в фоне, поэтому о нём сообщают уведомлением — так же,
     * как о пришедшем сообщении. Иначе результат некуда было бы деть: ход,
     * в котором она начала съёмку, давно закончился.
     */
    private void onPhotoOutcome(long chatId, boolean ready, String message) {
        String text = ready
                ? ("Your photo is ready.\n\n%s\nFilename: %s\n\nOpen the chat and send it with "
                   + "send_telegram_message if you like it, or take another one. Mention the filename "
                   + "in your diary — you may want to reuse the photo later.")
                        .formatted(lookAtOwnPhoto(message), message)
                : "Your photo did not work out: " + message + ". You can try again or let it go.";

        Toolbox available = new Toolbox();
        available.add(tools.open(chatId));
        loop.submit(new Notification(text, available));
        log.info("Уведомление о снимке поставлено в очередь");
    }

    /** Последняя реплика в контексте — чтобы подагент искал по делу, а не вообще. */
    /**
     * Собственный снимок она сначала разглядывает, а потом решает, отправлять ли.
     * Без описания выбор делается вслепую: «файл готов» — и всё.
     */
    private String lookAtOwnPhoto(String fileName) {
        if (vision == null) {
            return "";
        }
        try {
            return vision.describe(config.camera().gallery().resolve(fileName),
                    Vision.Kind.PHOTO, loop == null ? List.of() : loop.context());
        } catch (RuntimeException e) {
            log.debug("Свой снимок не разглядеть: {}", e.getMessage());
            return "";
        }
    }

    private String currentSituation() {
        var context = loop.context();
        return context.isEmpty() ? "" : String.valueOf(context.getLast().content());
    }

    private void onAuthorizationState(TdApi.UpdateAuthorizationState update) {
        switch (update.authorizationState) {
            case TdApi.AuthorizationStateReady ignored -> log.info("Авторизация пройдена");
            case TdApi.AuthorizationStateClosing ignored -> log.info("Закрываемся...");
            case TdApi.AuthorizationStateClosed ignored -> log.info("Закрыто");
            case TdApi.AuthorizationStateLoggingOut ignored -> log.info("Выходим из аккаунта...");
            default -> log.debug("Состояние авторизации: {}",
                    update.authorizationState.getClass().getSimpleName());
        }
    }

    /** Состояние связи стоит видеть в логе: половина бед — с сетью, а не с ботом. */
    private void onConnectionState(TdApi.UpdateConnectionState update) {
        switch (update.state) {
            case TdApi.ConnectionStateReady ignored -> log.info("Связь с Telegram есть");
            case TdApi.ConnectionStateConnecting ignored ->
                    log.info("Подключаюсь к Telegram... если надолго — проверь VPN или прокси");
            case TdApi.ConnectionStateConnectingToProxy ignored -> log.info("Подключаюсь через прокси...");
            case TdApi.ConnectionStateWaitingForNetwork ignored -> log.info("Жду сеть...");
            case TdApi.ConnectionStateUpdating ignored -> log.info("Догоняю пропущенное...");
            default -> log.debug("Состояние связи: {}", update.state.getClass().getSimpleName());
        }
    }

    private void onNewMessage(TdApi.UpdateNewMessage update) {
        if (loop == null) {
            return;
        }
        TdApi.Message message = update.message;
        if (message.isOutgoing) {
            return;
        }

        long senderId = message.senderId instanceof TdApi.MessageSenderUser user ? user.userId : 0L;
        if (senderId == config.ownerId() && rest != null) {
            rest.wakeUp();   // сообщение владельца поднимает, даже если она отошла
        }
        // судим по чату, а не только по отправителю: в разрешённой группе пишут
        // и незнакомые, и это нормально — переписка там общая
        if (!lockdown.allows(telegram.chatCached(message.chatId), telegram.isContact(senderId))) {
            log.debug("Пропускаю сообщение из чата {} — он вне круга общения", message.chatId);
            return;
        }

        // заглушённый чат — просьба уделять ему меньше внимания, а не игнорировать
        // совсем: иначе о нём вообще ничего не узнать. Чтобы отсечь начисто,
        // чат убирают в архив
        if (telegram.isMuted(message.chatId) && random.nextDouble() < MUTED_IGNORE_CHANCE) {
            log.debug("Заглушённый чат {} — на этот раз пропускаю", message.chatId);
            return;
        }

        // обработку уносим с потока апдейтов: она ходит в сеть и длится секунды
        Thread.ofVirtual().start(() -> {
            try {
                if (handledAsCommand(message, senderId)) {
                    return;
                }
                notifyAbout(message, senderId);
            } catch (RuntimeException e) {
                log.error("Не удалось построить уведомление", e);
            }
        });
    }

    private void noticeLater() {
        int seconds = config.noticeDelaySeconds();
        if (seconds <= 0) {
            return;
        }
        try {
            Thread.sleep(java.time.Duration.ofMillis(random.nextInt(seconds * 1000)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Разбирает непрочитанное, накопившееся пока её не было.
     * <p>
     * Telegram не присылает уведомления о том, что пришло до запуска. Без этого
     * всё, написанное за ночь или за время простоя, остаётся незамеченным
     * навсегда — она просто не узнает, что ей писали.
     */
    private void catchUpOnUnread() {
        try {
            // от старых к новым: так разговор восстанавливается в том порядке,
            // в каком он шёл
            List<TdApi.Chat> chats = new java.util.ArrayList<>(telegram.chats(CATCH_UP_CHATS));
            java.util.Collections.reverse(chats);

            int found = 0;
            for (TdApi.Chat chat : chats) {
                if (chat.unreadCount == 0 || chat.lastMessage == null || chat.lastMessage.isOutgoing) {
                    continue;
                }
                long senderId = chat.lastMessage.senderId instanceof TdApi.MessageSenderUser user ? user.userId : 0L;
                if (!lockdown.allows(chat, telegram.isContact(senderId))) {
                    continue;
                }
                notifyAbout(chat.lastMessage, senderId);
                found++;
            }
            if (found > 0) {
                log.info("Пока меня не было, написали в {} чат(ов)", found);
            }
        } catch (RuntimeException e) {
            log.warn("Не удалось разобрать непрочитанное: {}", e.getMessage());
        }
    }

    /** Команды владельца — короткие служебные вопросы, ради них будить модель незачем. */
    private boolean handledAsCommand(TdApi.Message message, long senderId) {
        if (senderId != config.ownerId() || !(message.content instanceof TdApi.MessageText text)) {
            return false;
        }
        String command = text.text.text.strip().toLowerCase();
        if (!command.equals("/version")) {
            return false;
        }
        String answer = "Raine %s, Java %s, дневник: %d записей"
                .formatted(version(), Runtime.version().feature(), new Diary(config.diaryDir()).size());
        actions.sendMessage(message.chatId, answer, message.id);
        log.info("Ответ на команду: {}", answer);
        return true;
    }

    private static String version() {
        String version = RaineApp.class.getPackage().getImplementationVersion();
        return version == null ? "из исходников" : version;
    }

    private void notifyAbout(TdApi.Message message, long senderId) {
        // человек не бросается к телефону в ту же секунду: он замечает уведомление
        // не сразу. Без этой паузы ответ приходит подозрительно мгновенно
        noticeLater();

        TdApi.Chat chat = telegram.chat(message.chatId);
        ChatKind kind = ChatKind.of(chat);

        String text = switch (kind) {
            case DM -> Notifications.directMessage(chat);
            case GROUP -> Notifications.groupMessage(chat, telegram.nameOf(senderId), senderId);
            case CHANNEL -> Notifications.channelPost(chat);
        };

        // уведомление устарело, если такое же уже стоит в очереди по этому чату
        loop.discardMatching(Notifications.openingTag(chat.id));

        // набор живёт вместе с уведомлением: инструменты одного чата не должны
        // оставаться доступными, когда разговор идёт уже в другом
        Toolbox available = new Toolbox();
        available.add(tools.open(chat.id));

        // сообщение владельца и закреплённые чаты идут вне очереди: остальное
        // подождёт, а это — то, ради чего она вообще здесь
        boolean important = senderId == config.ownerId()
                || (config.behaviour().wakeOnPinnedChat() && telegram.isPinned(chat.id));
        if (important) {
            loop.submitFirst(new Notification(text, available));
            log.info("Уведомление из чата \"{}\" — вне очереди", chat.title);
        } else {
            loop.submit(new Notification(text, available));
            log.info("Уведомление из чата \"{}\" поставлено в очередь", chat.title);
        }
    }

    /** Остановка по непоправимой причине: разбудить main, чтобы процесс завершился. */
    private void stopEverything() {
        try {
            close();
        } catch (Exception e) {
            log.warn("При остановке что-то пошло не так: {}", e.getMessage());
        }
    }

    @Override
    public void close() throws Exception {
        if (spontaneity != null) {
            spontaneity.close();
        }
        if (brain != null) {
            brain.interrupt();
        }
        client.close();
    }
}
