package ru.rainedev.raine.cli;

import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import java.time.Instant;
import ru.rainedev.raine.config.Config;

/**
 * Заглянуть в Telegram глазами самой Raine и ничего не делать.
 * <p>
 * Когда она не отвечает, первый вопрос — дошло ли сообщение вообще. По логу
 * этого не видно: если событие не пришло, в логе просто пусто, и непонятно,
 * молчит клиент или молчит собеседник. Здесь видно то же, что видит она:
 * список чатов, непрочитанное и последние реплики.
 */
public final class PeekCommand {

    private static final int CHATS = 15;
    private static final int MESSAGES = 5;

    private PeekCommand() {}

    public static void run(SimpleTelegramClient client, Config config) {
        TdApi.User me = client.send(new TdApi.GetMe()).join();
        System.out.printf("Я: %s %s (id %d)%n", me.firstName, me.lastName, me.id);
        System.out.printf("Владелец по настройкам: %d%n%n", config.ownerId());

        TdApi.GetChats request = new TdApi.GetChats();
        request.chatList = new TdApi.ChatListMain();
        request.limit = CHATS;
        long[] chatIds = client.send(request).join().chatIds;
        System.out.println("Чатов в списке: " + chatIds.length);

        for (long chatId : chatIds) {
            TdApi.Chat chat = client.send(new TdApi.GetChat(chatId)).join();
            System.out.printf("%n— %s (chat_id %d), непрочитанных %d%n",
                    chat.title, chat.id, chat.unreadCount);
            if (chat.lastMessage != null) {
                System.out.printf("  последнее: %s%n", describe(chat.lastMessage));
            }
        }

        long ownerChat = config.ownerId();
        System.out.printf("%n=== личный чат с владельцем (%d) ===%n", ownerChat);
        try {
            TdApi.Messages history = client.send(
                    new TdApi.GetChatHistory(ownerChat, 0, 0, MESSAGES, false)).join();
            if (history.messages == null || history.messages.length == 0) {
                System.out.println("пусто — сюда ничего не приходило");
            }
            for (TdApi.Message message : history.messages) {
                System.out.println("  " + describe(message));
            }
        } catch (RuntimeException e) {
            System.out.println("не удалось прочитать: " + e.getMessage());
        }
    }

    private static String describe(TdApi.Message message) {
        String text = message.content instanceof TdApi.MessageText content
                ? content.text.text
                : message.content.getClass().getSimpleName();
        long sender = message.senderId instanceof TdApi.MessageSenderUser user ? user.userId : 0;
        return "[%s] от %d%s: %s".formatted(
                Instant.ofEpochSecond(message.date), sender,
                message.isOutgoing ? " (моё)" : "", text);
    }
}
