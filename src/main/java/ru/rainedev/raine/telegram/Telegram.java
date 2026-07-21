package ru.rainedev.raine.telegram;

import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Фасад над TDLib. Вызовы блокирующие: цикл живёт на виртуальном потоке,
 * поэтому ожидание ответа ничего не стоит и код читается сверху вниз.
 */
public final class Telegram {

    private static final Logger log = LoggerFactory.getLogger(Telegram.class);

    private final SimpleTelegramClient client;
    private final long myId;
    private final String characterName;
    private final Map<Long, String> nameCache = new ConcurrentHashMap<>();

    /**
     * Свойства чата спрашиваются по нескольку раз на каждое сообщение: заглушён ли,
     * закреплён ли, можно ли с ним говорить. Каждый такой вопрос — обращение
     * к Telegram, а их число ограничено.
     */
    private final Map<Long, Cached> chatCache = new ConcurrentHashMap<>();

    private record Cached(TdApi.Chat chat, long at) {
        boolean fresh() {
            return System.currentTimeMillis() - at < CHAT_CACHE_MILLIS;
        }
    }

    private static final long CHAT_CACHE_MILLIS = 15_000;

    /**
     * Одно и то же сообщение за ход спрашивается по нескольку раз: проверка
     * цитаты, пересылка, правка, удаление. Живёт столько же, сколько чат.
     */
    private final ShortLivedCache<String, TdApi.Message> messageCache = new ShortLivedCache<>(CHAT_CACHE_MILLIS);

    /** Ждём распознавание не дольше полуминуты: дальше разговор уже остыл. */
    private static final long RECOGNITION_TICK = 500;
    private static final int RECOGNITION_TIMEOUT_TICKS = 60;

    private Boolean premium;

    private final Throttle throttle = new Throttle();

    /** Каждое обращение к Telegram проходит через ограничитель. */
    private SimpleTelegramClient throttled() {
        throttle.allow();
        return client;
    }

    public Telegram(SimpleTelegramClient client, String characterName) {
        this.client = client;
        this.characterName = characterName;
        this.myId = client.getMeAsync().join().id;
    }

    public long myId() {
        return myId;
    }

    /** Свежие данные: непрочитанное и последнее сообщение меняются постоянно. */
    public TdApi.Chat chat(long chatId) {
        TdApi.Chat chat = throttled().send(new TdApi.GetChat(chatId)).join();
        chatCache.put(chatId, new Cached(chat, System.currentTimeMillis()));
        return chat;
    }

    /**
     * То же, но можно отдать недавний ответ. Для вопросов вроде «заглушён ли чат»
     * пятнадцати секунд давности достаточно, а обращение экономится.
     */
    public TdApi.Chat chatCached(long chatId) {
        Cached cached = chatCache.get(chatId);
        return cached != null && cached.fresh() ? cached.chat() : chat(chatId);
    }

    /**
     * Имя отправителя так, как его видит модель.
     * <p>
     * Свои сообщения помечаются особо — иначе она не отличает свои реплики
     * от чужих. К имени добавляется @username: именно по нему она потом
     * находит человека поиском, чтобы написать первой.
     */
    public String nameOf(long id) {
        if (id == 0) {
            return "";
        }
        if (id == myId) {
            return "You (" + characterName + ")";
        }
        return nameCache.computeIfAbsent(id, key -> {
            try {
                TdApi.User user = throttled().send(new TdApi.GetUser(key)).join();
                String name = (user.firstName + " " + user.lastName).strip();
                String username = usernameOf(user);
                if (!username.isEmpty()) {
                    name = name.isEmpty() ? "@" + username : name + " (@" + username + ")";
                }
                // имя человек задаёт себе сам, значит там может оказаться что угодно
                return ru.rainedev.raine.phone.ControlMarkers.sanitize(name);
            } catch (RuntimeException userLookupFailed) {
                try {
                    return ru.rainedev.raine.phone.ControlMarkers.sanitize(chat(key).title);
                } catch (RuntimeException e) {
                    log.debug("Не удалось определить имя для id {}", key);
                    return "";
                }
            }
        });
    }

    private static String usernameOf(TdApi.User user) {
        if (user.usernames == null || user.usernames.activeUsernames == null
                || user.usernames.activeUsernames.length == 0) {
            return "";
        }
        return user.usernames.activeUsernames[0];
    }

    /** История чата от старых к новым — в порядке чтения. */
    public List<TdApi.Message> history(long chatId, int limit) {
        List<TdApi.Message> collected = new ArrayList<>();
        long fromMessageId = 0;
        while (collected.size() < limit) {
            TdApi.Messages page = client
                    .send(new TdApi.GetChatHistory(chatId, fromMessageId, 0, Math.min(50, limit), false))
                    .join();
            if (page.messages == null || page.messages.length == 0) {
                break;
            }
            collected.addAll(List.of(page.messages));
            fromMessageId = page.messages[page.messages.length - 1].id;
        }
        List<TdApi.Message> ordered = new ArrayList<>(collected.subList(0, Math.min(collected.size(), limit)));
        ordered.sort((a, b) -> Long.compare(a.id, b.id));
        return ordered;
    }

    /** Чаты из основного списка, самые свежие первыми. */
    public List<TdApi.Chat> chats(int limit) {
        TdApi.GetChats request = new TdApi.GetChats();
        request.chatList = new TdApi.ChatListMain();
        request.limit = limit;
        TdApi.Chats chats = throttled().send(request).join();
        List<TdApi.Chat> result = new ArrayList<>();
        for (long id : chats.chatIds) {
            try {
                result.add(chat(id));
            } catch (RuntimeException e) {
                log.debug("Чат {} недоступен", id);
            }
        }
        return result;
    }

    /**
     * Подгружает список чатов с сервера. Пока он не загружен, поиск по своим
     * чатам возвращает пустоту — TDLib ищет только по тому, что уже известно.
     */
    public void loadChats(int limit) {
        try {
            TdApi.LoadChats request = new TdApi.LoadChats();
            request.chatList = new TdApi.ChatListMain();
            request.limit = limit;
            throttled().send(request).join();
        } catch (RuntimeException e) {
            // «больше нечего загружать» — обычный ответ, а не ошибка
            log.debug("Список чатов загружен полностью: {}", e.getMessage());
        }
    }

    /**
     * Поиск человека или чата по всем доступным путям сразу: по своим чатам
     * находится только то, с кем уже есть переписка, публичный поиск отдаёт
     * в основном каналы, а человек из контактов, которому ещё не писали,
     * не находится ни там, ни там.
     * <p>
     * Найденное делится надвое, и разница существенная: со «своими» разговор
     * продолжают с того места, где он встал, а к «чужим» приходят с нуля,
     * и это совсем другой разговор.
     *
     * @param mine      те, с кем переписка уже есть, и люди из контактов
     * @param elsewhere публичные чаты и каналы, которые о ней ничего не знают
     */
    public record Found(List<TdApi.Chat> mine, List<TdApi.Chat> elsewhere) {
        public boolean isEmpty() {
            return mine.isEmpty() && elsewhere.isEmpty();
        }
    }

    public Found searchChats(String query, int limit) {
        String bare = query.startsWith("@") ? query.substring(1) : query;
        List<TdApi.Chat> mine = new ArrayList<>();
        List<TdApi.Chat> elsewhere = new ArrayList<>();

        // люди из контактов — даже те, с кем переписки ещё не было
        attempt("контакты", () -> {
            TdApi.SearchContacts request = new TdApi.SearchContacts();
            request.query = bare;
            request.limit = limit;
            for (long userId : throttled().send(request).join().userIds) {
                add(mine, privateChat(userId));
            }
        });

        attempt("свои чаты", () -> {
            TdApi.SearchChatsOnServer request = new TdApi.SearchChatsOnServer();
            request.query = bare;
            request.limit = limit;
            for (long id : throttled().send(request).join().chatIds) {
                add(mine, chat(id));
            }
        });

        if (query.startsWith("@")) {
            attempt("публичный чат", () -> {
                TdApi.SearchPublicChat request = new TdApi.SearchPublicChat();
                request.username = bare;
                add(elsewhere, throttled().send(request).join());
            });
        }

        attempt("публичный поиск", () -> {
            TdApi.SearchPublicChats publicSearch = new TdApi.SearchPublicChats();
            publicSearch.query = bare;
            for (long id : throttled().send(publicSearch).join().chatIds) {
                add(elsewhere, chat(id));
            }
        });

        // то, что уже нашлось среди своих, во второй список не попадает
        java.util.Set<Long> known = mine.stream().map(chat -> chat.id).collect(java.util.stream.Collectors.toSet());
        elsewhere.removeIf(chat -> known.contains(chat.id));

        return new Found(trim(mine, limit), trim(elsewhere, limit));
    }

    private static List<TdApi.Chat> trim(List<TdApi.Chat> chats, int limit) {
        return chats.size() > limit ? chats.subList(0, limit) : chats;
    }

    /**
     * Какие реакции Telegram примет для этого сообщения — его же словами.
     * <p>
     * Форму эмодзи угадывать нельзя: одни хранятся с невидимым вариационным
     * селектором, другие без, и набор отличается от чата к чату. Проще спросить.
     */
    public List<String> availableReactions(long chatId, long messageId) {
        List<String> emoji = new ArrayList<>();
        try {
            TdApi.GetMessageAvailableReactions request = new TdApi.GetMessageAvailableReactions();
            request.chatId = chatId;
            request.messageId = messageId;
            request.rowSize = 8;
            TdApi.AvailableReactions available = throttled().send(request).join();
            collect(emoji, available.topReactions);
            collect(emoji, available.recentReactions);
            collect(emoji, available.popularReactions);
        } catch (RuntimeException e) {
            log.debug("Список доступных реакций не получен: {}", e.getMessage());
        }
        return emoji;
    }

    private static void collect(List<String> into, TdApi.AvailableReaction[] reactions) {
        if (reactions == null) {
            return;
        }
        for (TdApi.AvailableReaction reaction : reactions) {
            if (reaction.type instanceof TdApi.ReactionTypeEmoji typed && !into.contains(typed.emoji)) {
                into.add(typed.emoji);
            }
        }
    }

    /**
     * Скачивает файл и отдаёт путь к нему. TDLib кладёт файлы к себе в каталог
     * сессии и переиспользует уже скачанное.
     */
    public Optional<java.nio.file.Path> download(int fileId) {
        try {
            TdApi.DownloadFile request = new TdApi.DownloadFile();
            request.fileId = fileId;
            request.priority = 16;
            request.synchronous = true;   // ждём готовности, а не подписываемся на обновления
            TdApi.File file = throttled().send(request).join();
            if (file.local == null || file.local.path == null || file.local.path.isEmpty()) {
                return Optional.empty();
            }
            java.nio.file.Path path = java.nio.file.Path.of(file.local.path);
            return java.nio.file.Files.exists(path) ? Optional.of(path) : Optional.empty();
        } catch (RuntimeException e) {
            log.debug("Не удалось скачать файл {}: {}", fileId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Распознаёт речь силами Telegram Premium — без внешних сервисов и оплаты
     * за минуты. Результат приходит не сразу, поэтому ждём его опросом.
     */
    public Optional<String> recognizeSpeech(long chatId, long messageId) {
        Optional<String> cached = message(chatId, messageId).flatMap(Telegram::transcriptOf);
        if (cached.isPresent()) {
            return cached;
        }
        if (!isPremium()) {
            return Optional.empty();
        }
        try {
            TdApi.RecognizeSpeech request = new TdApi.RecognizeSpeech();
            request.chatId = chatId;
            request.messageId = messageId;
            throttled().send(request).join();
        } catch (RuntimeException e) {
            log.debug("Распознавание не запустилось: {}", e.getMessage());
            return Optional.empty();
        }

        for (int waited = 0; waited < RECOGNITION_TIMEOUT_TICKS; waited++) {
            try {
                Thread.sleep(RECOGNITION_TICK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            Optional<String> ready = message(chatId, messageId).flatMap(Telegram::transcriptOf);
            if (ready.isPresent()) {
                return ready;
            }
        }
        log.debug("Распознавание речи не успело за отведённое время");
        return Optional.empty();
    }

    /** Есть ли премиум — без него распознавания не будет. */
    public boolean isPremium() {
        if (premium == null) {
            try {
                premium = throttled().send(new TdApi.GetUser(myId)).join().isPremium;
                if (!premium) {
                    log.info("Премиума нет: голосовые останутся нераспознанными");
                }
            } catch (RuntimeException e) {
                premium = false;
            }
        }
        return premium;
    }

    private static Optional<String> transcriptOf(TdApi.Message message) {
        TdApi.SpeechRecognitionResult result = switch (message.content) {
            case TdApi.MessageVoiceNote voice -> voice.voiceNote.speechRecognitionResult;
            case TdApi.MessageVideoNote video -> video.videoNote.speechRecognitionResult;
            case null, default -> null;
        };
        if (result instanceof TdApi.SpeechRecognitionResultText text && !text.text.isBlank()) {
            return Optional.of(text.text);
        }
        return Optional.empty();
    }

    /** Полная аватарка чата — та, что открывается по нажатию, а не миниатюра. */
    public Optional<TdApi.ChatPhoto> chatPhoto(long chatId) {
        try {
            TdApi.Chat chat = chat(chatId);
            if (chat.type instanceof TdApi.ChatTypePrivate priv) {
                TdApi.UserFullInfo info = throttled().send(new TdApi.GetUserFullInfo(priv.userId)).join();
                return Optional.ofNullable(info.photo);
            }
            // у групп и каналов полной истории аватарок нет — берём текущую
            if (chat.photo == null) {
                return Optional.empty();
            }
            TdApi.ChatPhoto current = new TdApi.ChatPhoto();
            TdApi.PhotoSize size = new TdApi.PhotoSize();
            size.photo = chat.photo.big;
            current.sizes = new TdApi.PhotoSize[] {size};
            return Optional.of(current);
        } catch (RuntimeException e) {
            log.debug("Аватарка чата {} недоступна: {}", chatId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Личный чат с пользователем. Объект создаётся локально, человек ничего не узнает. */
    public TdApi.Chat privateChat(long userId) {
        TdApi.CreatePrivateChat request = new TdApi.CreatePrivateChat();
        request.userId = userId;
        return throttled().send(request).join();
    }

    /** Закреплён ли чат — закрепляют то, что важно, и это стоит замечать. */
    public boolean isPinned(long chatId) {
        try {
            TdApi.Chat chat = chatCached(chatId);
            if (chat.positions == null) {
                return false;
            }
            for (TdApi.ChatPosition position : chat.positions) {
                if (position.isPinned) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Заглушён ли чат — по этому видно, насколько он важен владельцу. */
    public boolean isMuted(long chatId) {
        try {
            TdApi.Chat chat = chatCached(chatId);
            return chat.notificationSettings != null && chat.notificationSettings.muteFor > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Записан ли человек в контакты — от этого зависит, позволено ли с ним говорить. */
    public boolean isContact(long userId) {
        try {
            return throttled().send(new TdApi.GetUser(userId)).join().isContact;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Сохраняет человека в контакты — так его потом можно найти по имени. */
    public void addContact(long userId, String firstName, String lastName) {
        TdApi.ImportedContact contact = new TdApi.ImportedContact();
        contact.firstName = firstName;
        contact.lastName = lastName == null ? "" : lastName;

        TdApi.AddContact request = new TdApi.AddContact();
        request.userId = userId;
        request.contact = contact;
        request.sharePhoneNumber = false;   // свой номер не раздаём
        throttled().send(request).join();

        nameCache.remove(userId);
    }

    public List<TdApi.User> contacts(int limit) {
        List<TdApi.User> result = new ArrayList<>();
        try {
            long[] ids = throttled().send(new TdApi.GetContacts()).join().userIds;
            for (int i = 0; i < Math.min(ids.length, limit); i++) {
                result.add(throttled().send(new TdApi.GetUser(ids[i])).join());
            }
        } catch (RuntimeException e) {
            log.debug("Контакты недоступны: {}", e.getMessage());
        }
        return result;
    }

    private static void add(List<TdApi.Chat> found, TdApi.Chat chat) {
        if (chat != null && found.stream().noneMatch(known -> known.id == chat.id)) {
            found.add(chat);
        }
    }

    private static void attempt(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.debug("Поиск «{}» не дал результата: {}", what, e.getMessage());
        }
    }

    /**
     * Поиск по тексту внутри чата, при желании только по одному отправителю.
     */
    /**
     * Найденное вместе с тем, сколько его всего. Без общего числа не понять,
     * показали ли всё или верхушку, — а от этого зависит, уточнять ли запрос.
     */
    public record FoundMessages(List<TdApi.Message> messages, int total) {
        public boolean isEmpty() {
            return messages.isEmpty();
        }
    }

    public FoundMessages searchMessages(long chatId, String query, long senderId, int limit,
                                        long fromDaysAgo, long toDaysAgo) {
        TdApi.SearchChatMessages request = new TdApi.SearchChatMessages();
        request.chatId = chatId;
        request.query = query;
        request.limit = limit;
        if (senderId != 0) {
            request.senderId = new TdApi.MessageSenderUser(senderId);
        }
        TdApi.FoundChatMessages found = throttled().send(request).join();
        if (found.messages == null) {
            return new FoundMessages(List.of(), 0);
        }
        // у поиска внутри чата нет диапазона дат в запросе — отсеиваем после
        List<TdApi.Message> messages = java.util.Arrays.stream(found.messages)
                .filter(message -> withinDays(message.date, fromDaysAgo, toDaysAgo))
                .toList();
        return new FoundMessages(messages, found.totalCount);
    }

    private static boolean withinDays(int date, long fromDaysAgo, long toDaysAgo) {
        long now = System.currentTimeMillis() / 1000;
        if (fromDaysAgo > 0 && date < now - fromDaysAgo * 86400) {
            return false;
        }
        return !(toDaysAgo > 0 && date > now - toDaysAgo * 86400);
    }

    /**
     * Поиск по всем чатам сразу — когда неизвестно, где это было сказано.
     *
     * @param fromDaysAgo не старше стольких дней назад, 0 — без ограничения
     * @param toDaysAgo   не позже стольких дней назад, 0 — до сих пор
     */
    public FoundMessages searchAllMessages(String query, int limit, long fromDaysAgo, long toDaysAgo) {
        try {
            TdApi.SearchMessages request = new TdApi.SearchMessages();
            request.query = query;
            request.limit = limit;
            // «между неделей назад и вчера» — обычный человеческий способ вспоминать
            long now = System.currentTimeMillis() / 1000;
            request.minDate = fromDaysAgo > 0 ? (int) (now - fromDaysAgo * 86400) : 0;
            request.maxDate = toDaysAgo > 0 ? (int) (now - toDaysAgo * 86400) : 0;
            TdApi.FoundMessages found = throttled().send(request).join();
            return found.messages == null
                    ? new FoundMessages(List.of(), 0)
                    : new FoundMessages(List.of(found.messages), found.totalCount);
        } catch (RuntimeException e) {
            log.debug("Поиск по всем чатам не удался: {}", e.getMessage());
            return new FoundMessages(List.of(), 0);
        }
    }

    /** Соседние сообщения — чтобы понять, о чём вообще шла речь. */
    public List<TdApi.Message> messagesAround(long chatId, long messageId, int before, int after) {
        TdApi.GetChatHistory request = new TdApi.GetChatHistory();
        request.chatId = chatId;
        request.fromMessageId = messageId;
        // отрицательное смещение захватывает и то, что было после искомого
        request.offset = -after;
        request.limit = after + 1 + before;
        TdApi.Messages messages = throttled().send(request).join();
        List<TdApi.Message> result = messages.messages == null
                ? new ArrayList<>() : new ArrayList<>(List.of(messages.messages));
        result.sort((a, b) -> Long.compare(a.id, b.id));
        return result;
    }

    /**
     * Стикеры, попадавшиеся на глаза: свои и присланные другими.
     * <p>
     * У стикера два разных идентификатора — собственный и файловый. Модель
     * оперирует первым, а Telegram при отправке ждёт второй, поэтому связь
     * между ними приходится помнить.
     */
    /**
     * Стикер так, как его понимает Telegram при отправке.
     * <p>
     * Здесь два разных идентификатора: локальный живёт только внутри текущей
     * сессии, а удалённый — постоянный, и именно им стикер отправляют. После
     * перезапуска локальный номер уже ничего не значит.
     *
     * @param remoteId постоянный идентификатор файла
     */
    public record StickerRef(int fileId, String remoteId, String emoji, int width, int height) {}

    private final Map<Long, StickerRef> stickerFiles = new ConcurrentHashMap<>();

    public void rememberSticker(TdApi.Sticker sticker) {
        if (sticker == null || sticker.sticker == null) {
            return;
        }
        String remote = sticker.sticker.remote == null ? "" : sticker.sticker.remote.id;
        stickerFiles.put(sticker.id, new StickerRef(sticker.sticker.id, remote,
                sticker.emoji == null ? "" : sticker.emoji, sticker.width, sticker.height));
    }

    /** @return что известно про стикер или пусто, если такой не попадался */
    public Optional<StickerRef> sticker(long stickerId) {
        return Optional.ofNullable(stickerFiles.get(stickerId));
    }

    /** Сохранённые и недавние стикеры — то, чем она реально пользуется. */
    public List<TdApi.Sticker> savedStickers(int limit) {
        List<TdApi.Sticker> result = new ArrayList<>();
        try {
            result.addAll(List.of(throttled().send(new TdApi.GetFavoriteStickers()).join().stickers));
        } catch (RuntimeException e) {
            log.debug("Избранные стикеры недоступны: {}", e.getMessage());
        }
        try {
            TdApi.GetRecentStickers recent = new TdApi.GetRecentStickers();
            for (TdApi.Sticker sticker : throttled().send(recent).join().stickers) {
                if (result.stream().noneMatch(known -> known.id == sticker.id)) {
                    result.add(sticker);
                }
            }
        } catch (RuntimeException e) {
            log.debug("Недавние стикеры недоступны: {}", e.getMessage());
        }
        result.forEach(this::rememberSticker);
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    /**
     * Отправленное сообщение сначала получает временный номер, и только потом
     * Telegram присылает настоящий. Если отдать модели временный, она не сможет
     * ни поправить свою же реплику, ни удалить её: правка уйдёт в никуда.
     */
    private final Map<Long, java.util.concurrent.CompletableFuture<Long>> pendingSends = new ConcurrentHashMap<>();

    public void onSendSucceeded(long temporaryId, long finalId) {
        pendingSends.computeIfAbsent(temporaryId, id -> new java.util.concurrent.CompletableFuture<>())
                .complete(finalId);
    }

    /** Отправка не удалась — знать об этом важнее, чем молча идти дальше. */
    public void onSendFailed(long temporaryId, String reason) {
        log.warn("Сообщение не отправилось: {}", reason);
        pendingSends.computeIfAbsent(temporaryId, id -> new java.util.concurrent.CompletableFuture<>())
                .complete(0L);
    }

    /**
     * Ждёт настоящий номер недолго: если Telegram молчит, отдаём тот, что есть, —
     * ход важнее точности номера.
     *
     * @return настоящий номер, 0 при неудачной отправке или временный, если не дождались
     */
    public long confirmedId(long temporaryId) {
        var pending = pendingSends.computeIfAbsent(temporaryId, id -> new java.util.concurrent.CompletableFuture<>());
        try {
            return pending.get(SEND_CONFIRMATION_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.debug("Telegram не подтвердил отправку {} — беру временный номер", temporaryId);
            return temporaryId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return temporaryId;
        } catch (java.util.concurrent.ExecutionException e) {
            return temporaryId;
        } finally {
            pendingSends.remove(temporaryId);
        }
    }

    private static final int SEND_CONFIRMATION_SECONDS = 5;

    public Optional<TdApi.Message> message(long chatId, long messageId) {
        return messageCache.optional(chatId + ":" + messageId, key -> {
            try {
                return Optional.ofNullable(throttled().send(new TdApi.GetMessage(chatId, messageId)).join());
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    /** Текст правили или сообщение удалили — прежний ответ больше не верен. */
    public void forgetMessage(long chatId, long messageId) {
        messageCache.forget(chatId + ":" + messageId);
    }

    public void typing(long chatId) {
        TdApi.SendChatAction action = new TdApi.SendChatAction();
        action.chatId = chatId;
        action.action = new TdApi.ChatActionTyping();
        throttled().send(action);
    }
}
