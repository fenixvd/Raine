package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;

class PendingConversationsTest {

    @TempDir
    Path dir;

    @Test
    void conversationSurvivesUntilTheNetworkIsBack() {
        // сеть отвалилась на остановке — разговор должен дожить до запуска,
        // а не исчезнуть вместе с процессом
        PendingConversations pending = new PendingConversations(dir.resolve("pending"));
        List<Message> conversation = List.of(
                Message.user("тебе написали"),
                new Message(Message.Role.ASSISTANT, "думаю",
                        List.of(new ToolCall("1", "function", new ToolCall.Function("open", "{}"))), null, null),
                Message.toolResult("1", "<chat>переписка</chat>"));

        pending.keep(conversation);

        List<Path> waiting = pending.waiting();
        assertEquals(1, waiting.size(), "отложенный разговор должен найтись");

        List<Message> restored = pending.read(waiting.getFirst());
        assertEquals(3, restored.size());
        assertEquals("тебе написали", restored.getFirst().content());
        // вызовы инструментов тоже сохраняются: без них пересказ беднее
        assertEquals("open", restored.get(1).toolCallsOrEmpty().getFirst().function().name());
        assertEquals("1", restored.getLast().toolCallId());
    }

    @Test
    void retoldConversationIsNotKeptAround() {
        PendingConversations pending = new PendingConversations(dir.resolve("pending"));
        pending.keep(List.of(Message.user("разговор")));

        Path file = pending.waiting().getFirst();
        pending.done(file);

        assertFalse(Files.exists(file), "пересказанное не должно пересказываться снова");
        assertTrue(pending.waiting().isEmpty());
    }

    @Test
    void nothingIsWrittenForAnEmptyConversation() {
        PendingConversations pending = new PendingConversations(dir.resolve("pending"));

        pending.keep(List.of());

        assertTrue(pending.waiting().isEmpty());
    }
}
