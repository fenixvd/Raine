package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

class WorkingMemoryTest {

    private static final class ScriptedLlm implements LlmClient {
        private final Deque<String> answers = new ArrayDeque<>();
        String lastPrompt = "";
        int chatCalls;

        ScriptedLlm willAnswer(String text) {
            answers.addLast(text);
            return this;
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            chatCalls++;
            lastPrompt = String.valueOf(history.getLast().content());
            String text = answers.isEmpty() ? "" : answers.removeFirst();
            Message message = new Message(Message.Role.ASSISTANT, text, List.of(), null, null);
            return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, message, "stop")),
                    ChatResponse.Usage.EMPTY);
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {1};
        }
    }

    private static final List<Message> CONTEXT = List.of(Message.user("мы весь день переписывались"));

    @Test
    void writesRememberedThings(@TempDir Path dir) {
        Path file = dir.resolve("working_memory.md");
        ScriptedLlm llm = new ScriptedLlm().willAnswer("- Обещала скинуть рецепт — последнее обновление: Jul 21");

        new WorkingMemory(file, llm, "Raine").update("промпт", CONTEXT);

        assertTrue(new WorkingMemory(file, llm, "Raine").read().contains("Обещала скинуть рецепт"));
    }

    @Test
    void stripsWrappersModelAddsOnItsOwn(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("working_memory.md");
        ScriptedLlm llm = new ScriptedLlm().willAnswer("""
                ```xml
                <things_to_remember>
                - Жду ответ от Жени — последнее обновление: Jul 21
                </things_to_remember>
                ```""");

        new WorkingMemory(file, llm, "Raine").update("промпт", CONTEXT);

        String saved = Files.readString(file);
        assertFalse(saved.contains("```"), saved);
        assertFalse(saved.contains("<things_to_remember>"), saved);
        assertTrue(saved.contains("Жду ответ от Жени"));
    }

    @Test
    void neverStoresToolCallMarkup(@TempDir Path dir) {
        Path file = dir.resolve("working_memory.md");
        ScriptedLlm llm = new ScriptedLlm()
                .willAnswer("<｜｜DSML｜｜invoke name=\"update_instructions\">мусор")
                .willAnswer("- Нормальный пункт — последнее обновление: Jul 21");

        new WorkingMemory(file, llm, "Raine").update("промпт", CONTEXT);

        String saved = new WorkingMemory(file, llm, "Raine").read();
        assertFalse(saved.contains("DSML"), saved);
        assertTrue(saved.contains("Нормальный пункт"));
    }

    @Test
    void keepsPreviousMemoryWhenModelFails(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("working_memory.md");
        Files.writeString(file, "- Важное дело — последнее обновление: Jul 20");
        ScriptedLlm llm = new ScriptedLlm().willAnswer("").willAnswer("").willAnswer("");

        new WorkingMemory(file, llm, "Raine").update("промпт", CONTEXT);

        assertTrue(Files.readString(file).contains("Важное дело"), "прежнюю память терять нельзя");
    }

    @Test
    void showsPreviousMemoryToModel(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("working_memory.md");
        Files.writeString(file, "- Незакрытое дело — последнее обновление: Jul 20");
        ScriptedLlm llm = new ScriptedLlm().willAnswer("- Незакрытое дело — последнее обновление: Jul 21");

        new WorkingMemory(file, llm, "Raine").update("промпт", CONTEXT);

        assertTrue(llm.lastPrompt.contains("Незакрытое дело"), "иначе незакрытые дела забудутся");
        assertTrue(llm.lastPrompt.contains("preserve"), llm.lastPrompt);
    }

    @Test
    void suffixCarriesStateIntoSystemPrompt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("working_memory.md");
        Files.writeString(file, "- Настроение: устала — последнее обновление: Jul 21");

        String suffix = new WorkingMemory(file, new ScriptedLlm(), "Raine").asPromptSuffix();

        assertTrue(suffix.contains("<things_to_remember>"));
        assertTrue(suffix.contains("устала"));
        assertTrue(suffix.contains("physical state"), "состояние должно влиять на тон ответов");
    }

    @Test
    void emptyMemoryAddsNothingToPrompt(@TempDir Path dir) {
        assertEquals("", new WorkingMemory(dir.resolve("нет.md"), new ScriptedLlm(), "Raine").asPromptSuffix());
    }

    @Test
    void emptyConversationIsNotSummarized(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm();

        new WorkingMemory(dir.resolve("working_memory.md"), llm, "Raine").update("промпт", List.of());

        assertEquals(0, llm.chatCalls);
    }
}
