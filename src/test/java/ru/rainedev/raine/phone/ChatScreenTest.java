package ru.rainedev.raine.phone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.prompt.Prompts;

class ChatScreenTest {

    private static final long MY_ID = 7759009453L;
    private static final long JOHN = 100L;

    private final MessageFormatter formatter =
            new MessageFormatter(id -> id == JOHN ? "John" : "Raine", MessageFormatter.ReplyLookup.NONE);
    private final ChatScreen screen = new ChatScreen(
            formatter,
            new Prompts(Path.of("prompts"), new Config.Character("Raine", "@raine_tyan", "RaineDev")));
    private final MessageFormatter.ChatView view = new MessageFormatter.ChatView(MY_ID, 0L);

    @Test
    void showsMessagesUnderChatTitle() {
        ChatScreen.Screen result = screen.render(
                chat(1L, "John", new TdApi.ChatTypePrivate(JOHN)),
                List.of(message(600L, "привет")),
                view);

        assertEquals(ChatKind.DM, result.kind());
        assertTrue(result.text().startsWith("You switched to the chat \"John\""), result.text());
        assertTrue(result.text().contains("привет"), result.text());
        assertTrue(result.text().contains("private chat with John"), result.text());
    }

    @Test
    void channelForbidsReplying() {
        TdApi.ChatTypeSupergroup type = new TdApi.ChatTypeSupergroup();
        type.isChannel = true;

        ChatScreen.Screen result = screen.render(chat(2L, "Новости", type), List.of(message(1L, "пост")), view);

        assertEquals(ChatKind.CHANNEL, result.kind());
        assertTrue(result.text().contains("can't respond in telegram channels"), result.text());
        assertTrue(result.text().contains("#forward_message"), result.text());
    }

    @Test
    void supergroupThatIsNotChannelStaysGroup() {
        TdApi.ChatTypeSupergroup type = new TdApi.ChatTypeSupergroup();
        type.isChannel = false;

        ChatScreen.Screen result = screen.render(chat(3L, "Тусовка", type), List.of(), view);

        assertEquals(ChatKind.GROUP, result.kind());
        assertTrue(result.text().contains("group chat called \"Тусовка\""), result.text());
    }

    @Test
    void warnsWhenChatIsEmpty() {
        ChatScreen.Screen result = screen.render(chat(4L, "Некто", new TdApi.ChatTypePrivate(555L)), List.of(), view);

        assertTrue(result.text().contains("This chat is empty"), result.text());
    }

    @Test
    void notificationHidesMessageText() {
        TdApi.Chat chat = chat(1L, "John", new TdApi.ChatTypePrivate(JOHN));

        String notification = Notifications.directMessage(chat);

        assertTrue(notification.contains("direct message from John"), notification);
        assertTrue(notification.contains("Use #open tool"), notification);
        assertFalse(notification.contains("привет"), "содержимое не должно попадать в уведомление");
    }

    @Test
    void openingTagMatchesNotificationStart() {
        // по этому же тегу устаревшее уведомление убирают из очереди
        assertTrue(Notifications.directMessage(chat(42L, "X", new TdApi.ChatTypePrivate(1L)))
                .startsWith(Notifications.openingTag(42L)));
    }

    private static TdApi.Chat chat(long id, String title, TdApi.ChatType type) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = id;
        chat.title = title;
        chat.type = type;
        return chat;
    }

    private static TdApi.Message message(long id, String text) {
        TdApi.Message message = new TdApi.Message();
        message.id = id;
        message.chatId = 1L;
        message.date = 1_780_000_000;
        message.senderId = new TdApi.MessageSenderUser(JOHN);
        message.content = new TdApi.MessageText(new TdApi.FormattedText(text, new TdApi.TextEntity[0]), null, null);
        return message;
    }
}
