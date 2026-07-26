package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;

/**
 * Уведомление о готовом снимке несёт единственный инструмент — открыть чат,
 * и потому попадает под «открыть сразу, не спрашивая модель». Открытый чат
 * не должен вытеснять текст уведомления: имя файла написано только в нём.
 */
class PhotoNotificationTest {

    /** Сразу заканчивает ход: интересен только текст, с которого ход начался. */
    private static final class SilentLlm implements LlmClient {
        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            Message answer = new Message(Message.Role.ASSISTANT, null,
                    List.of(new ToolCall("1", "function", new ToolCall.Function("wait", "{}"))), null, null);
            return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, answer, "stop")),
                    ChatResponse.Usage.EMPTY);
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {1};
        }
    }

    private static Toolbox chatOpeningOnly() {
        return new Toolbox(Tool.simple("open", "", arguments -> "<chat>переписка</chat>"));
    }

    private static String firstSeen(Notification notification) {
        NotificationLoop loop = new NotificationLoop(
                new SilentLlm(), () -> "промпт", new Toolbox(), 40_000);
        loop.process(notification);
        return String.valueOf(loop.context().getFirst().content());
    }

    @Test
    void sheLearnsThePhotoIsReadyEvenThoughTheChatOpensItself() {
        // без этого она ждёт снимок, который уже лежит готовым: в переписке
        // о нём ни слова, а текст уведомления заменялся открытым чатом
        String seen = firstSeen(new Notification(
                "Your photo is ready.\nFilename: 123.webp", chatOpeningOnly(), true));

        assertTrue(seen.contains("Filename: 123.webp"), "имя файла должно дойти до неё: " + seen);
        assertTrue(seen.contains("переписка"), "открытый чат тоже нужен: " + seen);
    }

    @Test
    void plainMessageNotificationIsStillReplacedByTheOpenedChat() {
        // «тебе написали» рядом с самой перепиской — пустое повторение
        String seen = firstSeen(new Notification("тебе написали", chatOpeningOnly()));

        assertTrue(seen.contains("переписка"));
        assertFalse(seen.contains("тебе написали"), "шапка уведомления здесь лишняя: " + seen);
    }
}
