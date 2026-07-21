package ru.rainedev.raine.telegram;

import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Всё, что меняет состояние на стороне Telegram, собрано здесь.
 * <p>
 * Отдельный интерфейс нужен ради безопасного режима: пока аккаунтом
 * пользуется другой клиент, второму нельзя ни писать, ни открывать чаты —
 * открытие пометит сообщения прочитанными на весь аккаунт.
 */
public interface TelegramActions {

    /** @return идентификатор отправленного сообщения, 0 если отправки не было */
    long sendMessage(long chatId, String text, Long replyToMessageId);

    void openChat(long chatId);

    void closeChat(long chatId);

    /** Снимает «печатает...»: иначе индикатор висит ещё несколько секунд после ухода. */
    void stopTyping(long chatId);

    void markRead(long chatId, long[] messageIds);

    /** Индикатор «печатает…» — тоже видимое действие на аккаунте. */
    void typing(long chatId);

    /** «Записывает голосовое…» — собеседник видит это и ждёт. */
    void recordingVoice(long chatId);

    void react(long chatId, long messageId, String emoji);

    /** Все сообщения разом: в Telegram это одна связка, а не несколько пересылок. */
    void forward(long toChatId, long fromChatId, long[] messageIds);

    void deleteMessage(long chatId, long messageId);

    /** Текст правится одним методом, подпись к медиа — другим. */
    void editText(long chatId, long messageId, String text, boolean isMedia);

    void blockAndLeave(long chatId);

    void sendSticker(long chatId, Telegram.StickerRef sticker, Long replyToMessageId);

    void saveSticker(Telegram.StickerRef sticker);

    void saveContact(long userId, String firstName, String lastName);

    void sendVoice(long chatId, java.nio.file.Path file, int seconds);

    void sendPhoto(long chatId, java.nio.file.Path file, String caption);

    void uploadingPhoto(long chatId);

    /** Статус «в сети». По нему собеседник видит, спит она или нет. */
    void setOnline(boolean online);

    /** Действует по-настоящему. */
    final class Live implements TelegramActions {

        private static final Logger log = LoggerFactory.getLogger(Live.class);

        private final SimpleTelegramClient client;
        private final Telegram telegram;
        private final Throttle throttle = new Throttle();

        /** Каждое действие тоже считается обращением к Telegram. */
        private SimpleTelegramClient throttled() {
            throttle.allow();
            return client;
        }

        public Live(SimpleTelegramClient client, Telegram telegram) {
            this.client = client;
            this.telegram = telegram;
        }

        @Override
        public long sendMessage(long chatId, String text, Long replyToMessageId) {
            TdApi.InputMessageText content = new TdApi.InputMessageText();
            content.text = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);

            TdApi.SendMessage request = new TdApi.SendMessage();
            request.chatId = chatId;
            request.inputMessageContent = content;
            if (replyToMessageId != null) {
                TdApi.InputMessageReplyToMessage replyTo = new TdApi.InputMessageReplyToMessage();
                replyTo.messageId = replyToMessageId;
                request.replyTo = replyTo;
            }
            return throttled().send(request).join().id;
        }

        @Override
        public void openChat(long chatId) {
            throttled().send(new TdApi.OpenChat(chatId)).join();
        }

        @Override
        public void closeChat(long chatId) {
            throttled().send(new TdApi.CloseChat(chatId)).join();
        }

        @Override
        public void stopTyping(long chatId) {
            TdApi.SendChatAction action = new TdApi.SendChatAction();
            action.chatId = chatId;
            action.action = new TdApi.ChatActionCancel();
            throttled().send(action);
        }

        @Override
        public void markRead(long chatId, long[] messageIds) {
            throttled().send(new TdApi.ViewMessages(chatId, messageIds, null, false)).join();
        }

        @Override
        public void typing(long chatId) {
            TdApi.SendChatAction action = new TdApi.SendChatAction();
            action.chatId = chatId;
            action.action = new TdApi.ChatActionTyping();
            throttled().send(action);
        }

        @Override
        public void recordingVoice(long chatId) {
            TdApi.SendChatAction action = new TdApi.SendChatAction();
            action.chatId = chatId;
            action.action = new TdApi.ChatActionRecordingVoiceNote();
            throttled().send(action);
        }


        @Override
        public void react(long chatId, long messageId, String emoji) {
            TdApi.AddMessageReaction request = new TdApi.AddMessageReaction();
            request.chatId = chatId;
            request.messageId = messageId;
            request.reactionType = new TdApi.ReactionTypeEmoji(emoji);
            // так реакция попадает в её недавние: набор со временем меняется сам,
            // как у человека
            request.updateRecentReactions = true;
            throttled().send(request).join();
        }

        @Override
        public void forward(long toChatId, long fromChatId, long[] messageIds) {
            TdApi.ForwardMessages request = new TdApi.ForwardMessages();
            request.chatId = toChatId;
            request.fromChatId = fromChatId;
            request.messageIds = messageIds;
            // именно пересылка, с указанием источника, а не копия чужого текста
            // от своего имени
            request.sendCopy = false;
            request.removeCaption = false;
            throttled().send(request).join();
        }

        @Override
        public void deleteMessage(long chatId, long messageId) {
            TdApi.DeleteMessages request = new TdApi.DeleteMessages();
            request.chatId = chatId;
            request.messageIds = new long[] {messageId};
            request.revoke = true;
            throttled().send(request).join();
        }

        @Override
        public void editText(long chatId, long messageId, String text, boolean isMedia) {
            TdApi.FormattedText formatted = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
            if (isMedia) {
                // подпись к фото или видео правится своим методом, иначе TDLib отказывает
                TdApi.EditMessageCaption request = new TdApi.EditMessageCaption();
                request.chatId = chatId;
                request.messageId = messageId;
                request.caption = formatted;
                throttled().send(request).join();
                return;
            }
            TdApi.InputMessageText content = new TdApi.InputMessageText();
            content.text = formatted;
            TdApi.EditMessageText request = new TdApi.EditMessageText();
            request.chatId = chatId;
            request.messageId = messageId;
            request.inputMessageContent = content;
            throttled().send(request).join();
        }

        @Override
        public void sendSticker(long chatId, Telegram.StickerRef sticker, Long replyToMessageId) {
            TdApi.InputMessageSticker content = new TdApi.InputMessageSticker();
            // по удалённому идентификатору, а не по локальному: локальный привязан
            // к сессии и после перезапуска указывает в пустоту
            content.sticker = sticker.remoteId().isEmpty()
                    ? new TdApi.InputFileId(sticker.fileId())
                    : new TdApi.InputFileRemote(sticker.remoteId());
            content.emoji = sticker.emoji();
            content.width = sticker.width();
            content.height = sticker.height();

            TdApi.SendMessage request = new TdApi.SendMessage();
            request.chatId = chatId;
            request.inputMessageContent = content;
            if (replyToMessageId != null) {
                TdApi.InputMessageReplyToMessage replyTo = new TdApi.InputMessageReplyToMessage();
                replyTo.messageId = replyToMessageId;
                request.replyTo = replyTo;
            }
            throttled().send(request).join();
        }

        @Override
        public void saveSticker(Telegram.StickerRef sticker) {
            TdApi.AddFavoriteSticker request = new TdApi.AddFavoriteSticker();
            request.sticker = sticker.remoteId().isEmpty()
                    ? new TdApi.InputFileId(sticker.fileId())
                    : new TdApi.InputFileRemote(sticker.remoteId());
            throttled().send(request).join();
        }

        @Override
        public void saveContact(long userId, String firstName, String lastName) {
            telegram.addContact(userId, firstName, lastName);
        }

        @Override
        public void sendPhoto(long chatId, java.nio.file.Path file, String caption) {
            TdApi.InputPhoto photo = new TdApi.InputPhoto();
            photo.photo = new TdApi.InputFileLocal(file.toAbsolutePath().toString());

            TdApi.InputMessagePhoto content = new TdApi.InputMessagePhoto();
            content.photo = photo;
            if (caption != null && !caption.isBlank()) {
                content.caption = new TdApi.FormattedText(caption, new TdApi.TextEntity[0]);
            }

            TdApi.SendMessage request = new TdApi.SendMessage();
            request.chatId = chatId;
            request.inputMessageContent = content;
            throttled().send(request).join();
        }

        @Override
        public void setOnline(boolean online) {
            TdApi.SetOption request = new TdApi.SetOption();
            request.name = "online";
            request.value = new TdApi.OptionValueBoolean(online);
            throttled().send(request);
        }

        @Override
        public void uploadingPhoto(long chatId) {
            TdApi.SendChatAction action = new TdApi.SendChatAction();
            action.chatId = chatId;
            action.action = new TdApi.ChatActionUploadingPhoto();
            throttled().send(action);
        }

        @Override
        public void sendVoice(long chatId, java.nio.file.Path file, int seconds) {
            TdApi.InputMessageVoiceNote content = new TdApi.InputMessageVoiceNote();
            content.voiceNote = new TdApi.InputFileLocal(file.toAbsolutePath().toString());
            content.duration = seconds;

            TdApi.SendMessage request = new TdApi.SendMessage();
            request.chatId = chatId;
            request.inputMessageContent = content;
            throttled().send(request).join();
        }

        @Override
        public void blockAndLeave(long chatId) {
            TdApi.Chat chat = throttled().send(new TdApi.GetChat(chatId)).join();

            TdApi.SetMessageSenderBlockList request = new TdApi.SetMessageSenderBlockList();
            // в личке блокируют человека, а не чат: с типом чата запрос проходит,
            // но никого не блокирует
            request.senderId = chat.type instanceof TdApi.ChatTypePrivate priv
                    ? new TdApi.MessageSenderUser(priv.userId)
                    : new TdApi.MessageSenderChat(chatId);
            request.blockList = new TdApi.BlockListMain();
            throttled().send(request).join();

            // из группы и канала просто уходят
            if (!(chat.type instanceof TdApi.ChatTypePrivate)) {
                try {
                    throttled().send(new TdApi.LeaveChat(chatId)).join();
                } catch (RuntimeException e) {
                    log.debug("Не удалось выйти из чата {}: {}", chatId, e.getMessage());
                }
            }
        }
    }


    /** Ничего не делает, только пишет в лог, что сделала бы. */
    final class ReadOnly implements TelegramActions {

        private static final Logger log = LoggerFactory.getLogger(ReadOnly.class);

        @Override
        public long sendMessage(long chatId, String text, Long replyToMessageId) {
            log.info("[только чтение] в чат {} ушло бы: {}", chatId, text);
            return 0;
        }

        @Override
        public void openChat(long chatId) {
            log.info("[только чтение] чат {} не открываю — это пометит сообщения прочитанными", chatId);
        }

        @Override
        public void closeChat(long chatId) {
            // закрывать нечего
        }

        @Override
        public void stopTyping(long chatId) {
            // индикатор и не показывался
        }

        @Override
        public void markRead(long chatId, long[] messageIds) {
            log.info("[только чтение] {} сообщений в чате {} остаются непрочитанными", messageIds.length, chatId);
        }

        @Override
        public void typing(long chatId) {
            // «печатает…» увидел бы собеседник — в безопасном режиме молчим
        }

        @Override
        public void recordingVoice(long chatId) {
            // индикатор увидел бы собеседник — в безопасном режиме молчим
        }

        @Override
        public void react(long chatId, long messageId, String emoji) {
            log.info("[только чтение] на сообщение {} в чате {} ушла бы реакция {}", messageId, chatId, emoji);
        }

        @Override
        public void forward(long toChatId, long fromChatId, long[] messageIds) {
            log.info("[только чтение] {} сообщений переслалось бы в чат {}", messageIds.length, toChatId);
        }

        @Override
        public void deleteMessage(long chatId, long messageId) {
            log.info("[только чтение] сообщение {} в чате {} удалилось бы", messageId, chatId);
        }

        @Override
        public void editText(long chatId, long messageId, String text, boolean isMedia) {
            log.info("[только чтение] сообщение {} стало бы: {}", messageId, text);
        }

        @Override
        public void sendSticker(long chatId, Telegram.StickerRef sticker, Long replyToMessageId) {
            log.info("[только чтение] в чат {} ушёл бы стикер {}", chatId, sticker.emoji());
        }

        @Override
        public void saveSticker(Telegram.StickerRef sticker) {
            log.info("[только чтение] стикер {} сохранился бы в избранное", sticker.fileId());
        }

        @Override
        public void saveContact(long userId, String firstName, String lastName) {
            log.info("[только чтение] в контакты записался бы {} ({})", firstName, userId);
        }

        @Override
        public void sendVoice(long chatId, java.nio.file.Path file, int seconds) {
            log.info("[только чтение] в чат {} ушло бы голосовое на {} с: {}", chatId, seconds, file);
        }

        @Override
        public void sendPhoto(long chatId, java.nio.file.Path file, String caption) {
            log.info("[только чтение] в чат {} ушло бы фото {} с подписью: {}", chatId, file, caption);
        }

        @Override
        public void uploadingPhoto(long chatId) {
            // индикатор увидел бы собеседник — в безопасном режиме молчим
        }

        @Override
        public void setOnline(boolean online) {
            // «был в сети» виден всем — в безопасном режиме статус не трогаем
        }

        @Override
        public void blockAndLeave(long chatId) {
            log.info("[только чтение] чат {} заблокировался бы", chatId);
        }
    }
}
