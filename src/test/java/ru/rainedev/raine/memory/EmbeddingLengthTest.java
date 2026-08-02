package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

/**
 * У модели эмбеддингов свой предел длины, и превышение она встречает отказом,
 * а не усечением: вектор не считается, и не вспоминается ничего. Проверено
 * по логам сервера — подробное описание фото занимало предел целиком.
 */
class EmbeddingLengthTest {

    /** Запоминает, что именно попросили посчитать. */
    private static final class RecordingLlm implements LlmClient {
        private String asked;

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double[] embedding(String input) {
            asked = input;
            return new double[] {1, 0, 0};
        }
    }

    private static String longText(int length) {
        return "я".repeat(length);
    }

    @Test
    void hugeContextIsCutBeforeAsking(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("мы ездили на Байкал", new double[] {1, 0, 0});
        RecordingLlm llm = new RecordingLlm();
        Memory memory = new DiaryMemory(diary, llm, 4000);

        memory.recall(List.of(Message.user(longText(50_000))));

        assertTrue(llm.asked.length() <= 8_000,
                "запрос ушёл длиной " + llm.asked.length() + " — эндпоинт такой отвергнет");
    }

    @Test
    void theEndOfConversationSurvivesTheCut() {
        String description = "описание фотографии, очень подробное. ".repeat(500);
        List<Message> context = List.of(Message.user(description), Message.user("а помнишь Байкал?"));

        String topic = DiaryMemory.topicOf(context);

        assertTrue(topic.length() <= 8_000, "длина не ограничена: " + topic.length());
        assertTrue(topic.contains("а помнишь Байкал?"),
                "обрезать надо начало: последнее сказанное и есть то, о чём спрашивают");
    }

    @Test
    void shortContextIsLeftAlone() {
        List<Message> context = List.of(Message.user("помнишь поездку?"));

        assertEquals("помнишь поездку?\n\n---\n\n", DiaryMemory.topicOf(context));
    }
}
