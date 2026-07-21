package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

class DiaryWriterTest {

    /** Отдаёт заготовленные пересказы и постоянный вектор. */
    private static final class ScriptedLlm implements LlmClient {
        private final Deque<String> answers = new ArrayDeque<>();
        private double[] vector;
        int chatCalls;

        ScriptedLlm willAnswer(String text) {
            answers.addLast(text);
            return this;
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            chatCalls++;
            String text = answers.isEmpty() ? "" : answers.removeFirst();
            Message message = new Message(Message.Role.ASSISTANT, text, List.of(), null, null);
            return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, message, "stop")),
                    ChatResponse.Usage.EMPTY);
        }

        /** Разный текст — разный вектор, иначе любые две записи выглядят копиями. */
        @Override
        public double[] embedding(String input) {
            if (vector != null) {
                return vector;
            }
            int hash = input.hashCode();
            return new double[] {(hash & 0xFF) / 255d, ((hash >> 8) & 0xFF) / 255d, ((hash >> 16) & 0xFF) / 255d};
        }
    }

    private static final List<Message> CONTEXT = List.of(Message.user("мы весь день переписывались"));

    private DiaryWriter writerFor(Path dir, ScriptedLlm llm) {
        return new DiaryWriter(new Diary(dir), llm, "запиши дневник", 0.97);
    }

    @Test
    void splitsSummaryIntoSeparateEntries(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm().willAnswer("""
                Сегодня ездили на Байкал, было холодно но красиво.
                ---
                Вечером обсуждали планы на отпуск в октябре.""");

        List<DiaryEntry> saved = writerFor(dir, llm).save("промпт", CONTEXT);

        assertEquals(2, saved.size());
        assertTrue(saved.getFirst().body().contains("Байкал"));
        assertTrue(saved.get(1).body().contains("отпуск"));
    }

    @Test
    void survivesBrokenSeparators(@TempDir Path dir) {
        // модель порой пишет разделитель с лишними пробелами
        ScriptedLlm llm = new ScriptedLlm().willAnswer("Первая запись про поездку.\n- --\nВторая запись про работу.");

        assertEquals(2, writerFor(dir, llm).save("промпт", CONTEXT).size());
    }

    @Test
    void skipsTooShortFragments(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm().willAnswer("Нормальная запись про поездку на Байкал.\n---\nок");

        assertEquals(1, writerFor(dir, llm).save("промпт", CONTEXT).size());
    }

    @Test
    void neverStoresToolCallMarkup(@TempDir Path dir) {
        // ровно та поломка, из-за которой в память попадал мусор
        ScriptedLlm llm = new ScriptedLlm()
                .willAnswer("<｜｜DSML｜｜invoke name=\"publish_diary_entries\">какой-то мусор</invoke>")
                .willAnswer("Нормальная запись про поездку на Байкал.");

        List<DiaryEntry> saved = writerFor(dir, llm).save("промпт", CONTEXT);

        assertEquals(1, saved.size());
        assertFalse(saved.getFirst().body().contains("DSML"));
        assertEquals(2, llm.chatCalls, "после мусора модель должна получить второй шанс");
    }

    @Test
    void givesUpInsteadOfSavingGarbage(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm()
                .willAnswer("<｜｜DSML｜｜tool_calls>")
                .willAnswer("<｜｜DSML｜｜tool_calls>")
                .willAnswer("<｜｜DSML｜｜tool_calls>");

        assertTrue(writerFor(dir, llm).save("промпт", CONTEXT).isEmpty(),
                "лучше не записать ничего, чем записать разметку");
    }

    @Test
    void retriesWhenModelAnswersWithNothing(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm().willAnswer("").willAnswer("Запись про поездку на Байкал.");

        assertEquals(1, writerFor(dir, llm).save("промпт", CONTEXT).size());
        assertEquals(2, llm.chatCalls);
    }

    @Test
    void doesNotStoreWhatIsAlreadyRemembered(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("Сегодня ездили на Байкал.", new double[] {1, 0, 0});

        ScriptedLlm llm = new ScriptedLlm().willAnswer("Сегодня ездили на Байкал, было красиво.");
        llm.vector = new double[] {1, 0, 0};
        List<DiaryEntry> saved = new DiaryWriter(diary, llm, "запиши дневник", 0.97).save("промпт", CONTEXT);

        assertTrue(saved.isEmpty(), "то же самое другими словами память не обогащает");
    }

    @Test
    void emptyConversationIsNotWorthSaving(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm();

        assertTrue(writerFor(dir, llm).save("промпт", List.of()).isEmpty());
        assertEquals(0, llm.chatCalls, "незачем дёргать модель впустую");
    }
}
