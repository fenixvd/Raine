package ru.rainedev.raine.phone;

import it.tdlight.jni.TdApi;

/**
 * Текст уведомления — то, что Raine видит, не открывая чат.
 * <p>
 * Содержимого сообщения здесь намеренно нет: как на экране блокировки,
 * видно только от кого пришло. Чтобы прочитать, нужно открыть чат.
 */
public final class Notifications {

    private Notifications() {}

    /** Открывающий тег: по нему же уведомление ищут, когда чат уже открыт и оно устарело. */
    public static String openingTag(long chatId) {
        return "<notification chat_id=\"%d\">\n".formatted(chatId);
    }

    public static String directMessage(TdApi.Chat chat) {
        return wrap(chat.id, "You received a direct message from %s (chat_id = %d)".formatted(chat.title, chat.id));
    }

    public static String groupMessage(TdApi.Chat chat, String senderName, long senderId) {
        return wrap(chat.id, "%s (user_id = %d) sent a message in group chat \"%s\" (chat_id = %d)"
                .formatted(senderName, senderId, chat.title, chat.id));
    }

    public static String channelPost(TdApi.Chat chat) {
        return wrap(chat.id, "Channel \"%s\" (chat_id=%d) created a new post".formatted(chat.title, chat.id));
    }

    private static String wrap(long chatId, String body) {
        return openingTag(chatId) + body + "\n</notification>\n"
                + "You don't have any chat open. Use #open tool to open the chat";
    }
}
