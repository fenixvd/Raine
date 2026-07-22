package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;

class OpenedChatToolsTest {

    /** Отвечает по сценарию: сначала открыть чат, потом действовать в нём. */
    private static final class ScriptedLlm implements LlmClient {
        private final Deque<String> plan = new ArrayDeque<>(List.of("open_second", "act", "wait"));

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            String next = plan.isEmpty() ? "wait" : plan.removeFirst();
            Message answer = new Message(Message.Role.ASSISTANT, null,
                    List.of(new ToolCall("1", "function", new ToolCall.Function(next, "{}"))), null, null);
            return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, answer, "stop")),
                    ChatResponse.Usage.EMPTY);
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {1};
        }
    }

    @Test
    void toolsOfTheOpenedChatLiveUntilTheEndOfTheTurn() {
        // набор пересобирается на каждом шаге: если добавленное живёт один шаг,
        // на следующем возвращаются инструменты прежнего чата — и реакция
        // или сообщение уходят не туда
        StringBuilder whereItWent = new StringBuilder();

        Toolbox forFirstChat = new Toolbox(
                Tool.simple("act", "", arguments -> {
                    whereItWent.append("первый чат");
                    return "ok";
                }),
                Tool.named("open_second").describedAs("").buildContextual((arguments, addTool) -> {
                    addTool.accept(Tool.simple("act", "", ignored -> {
                        whereItWent.append("второй чат");
                        return "ok";
                    }));
                    return "открыла второй чат";
                }));

        NotificationLoop loop = new NotificationLoop(
                new ScriptedLlm(), () -> "промпт", new Toolbox(), 40_000);
        loop.process(new Notification("сообщение", forFirstChat));

        assertEquals("второй чат", whereItWent.toString(),
                "действие должно уйти в тот чат, который она открыла");
        assertTrue(loop.context().size() > 2);
    }
}
