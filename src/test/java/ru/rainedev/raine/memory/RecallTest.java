package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;

class RecallTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Веб-поиска нет: без ключа он и в жизни недоступен. */
    private static final ru.rainedev.raine.tools.WebSearch NO_WEB =
            new ru.rainedev.raine.tools.WebSearch("http://localhost", "");

    /** Изображает подагента: сначала ищет, потом отвечает. */
    private static final class ScriptedLlm implements LlmClient {
        private final Deque<ChatResponse> answers = new ArrayDeque<>();
        private ChatResponse repeating;
        int chatCalls;

        ScriptedLlm searches(String cue) {
            answers.addLast(response("", List.of(new ToolCall("c" + answers.size(), "function",
                    new ToolCall.Function("query", "{\"text\":\"" + cue + "\"}")))));
            return this;
        }

        ScriptedLlm says(String text) {
            answers.addLast(response(text, List.of()));
            return this;
        }

        ScriptedLlm alwaysSearches() {
            repeating = response("", List.of(new ToolCall("c", "function",
                    new ToolCall.Function("query", "{\"text\":\"поездка\"}"))));
            return this;
        }

        ScriptedLlm alwaysSaysWithoutSearching() {
            repeating = response("просто отвечаю без поиска", List.of());
            return this;
        }

        private static ChatResponse response(String text, List<ToolCall> calls) {
            Message message = new Message(Message.Role.ASSISTANT, text, calls, null, null);
            return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, message, "stop")),
                    ChatResponse.Usage.EMPTY);
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            chatCalls++;
            if (!answers.isEmpty()) {
                return answers.removeFirst();
            }
            return repeating != null ? repeating : response("нечего добавить", List.of());
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {1, 0, 0};
        }
    }

    private static Diary diaryWith(Path dir, String... bodies) {
        Diary diary = new Diary(dir);
        for (String body : bodies) {
            diary.save(body, new double[] {1, 0, 0});
        }
        return diary;
    }

    private static String call(Tool tool, String query) {
        try {
            return tool.call(MAPPER.readTree("{\"query\":\"" + query + "\"}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void searchesDiaryAndReturnsSummary(@TempDir Path dir) {
        Diary diary = diaryWith(dir, "Серёжа ездил на Байкал в июле");
        ScriptedLlm llm = new ScriptedLlm().searches("Байкал").says("Он ездил на Байкал в июле.");

        String answer = call(new Recall(diary, llm, "характер", NO_WEB).asTool(() -> ""), "что я знаю про поездки Серёжи?");

        assertEquals("Он ездил на Байкал в июле.", answer);
    }

    @Test
    void asksForMoreContextOnShortQuery(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm();

        String answer = call(new Recall(diaryWith(dir, "что-то"), llm, "характер", NO_WEB).asTool(() -> ""), "кто");

        assertTrue(answer.contains("too short query"), answer);
        assertEquals(0, llm.chatCalls, "короткий запрос отсекается до обращения к модели");
    }

    @Test
    void insistsOnSearchingBeforeAnswering(@TempDir Path dir) {
        // подагент обязан заглянуть в память, а не сочинять из головы
        ScriptedLlm llm = new ScriptedLlm().says("и так всё знаю").searches("Байкал").says("Ездил на Байкал.");

        String answer = call(new Recall(diaryWith(dir, "поездка на Байкал"), llm, "характер", NO_WEB).asTool(() -> ""),
                "что я знаю про поездки?");

        assertEquals("Ездил на Байкал.", answer);
        assertEquals(3, llm.chatCalls);
    }

    @Test
    void stopsWhenSubagentKeepsSearchingForever(@TempDir Path dir) {
        // в оригинале цикл не ограничен и способен крутиться бесконечно
        ScriptedLlm llm = new ScriptedLlm().alwaysSearches();

        call(new Recall(diaryWith(dir, "поездка на Байкал"), llm, "характер", NO_WEB).asTool(() -> ""), "расскажи всё");

        assertTrue(llm.chatCalls <= 8, "подагент должен останавливаться, было вызовов: " + llm.chatCalls);
    }

    @Test
    void stopsWhenSubagentNeverSearches(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm().alwaysSaysWithoutSearching();

        String answer = call(new Recall(diaryWith(dir, "поездка"), llm, "характер", NO_WEB).asTool(() -> ""), "расскажи всё");

        assertTrue(llm.chatCalls <= 8, "было вызовов: " + llm.chatCalls);
        assertFalse(answer.isBlank(), "хоть что-то вернуть надо");
    }

    @Test
    void tellsSubagentWhenEverythingWasAlreadyGiven(@TempDir Path dir) {
        // подсказка, до которой в оригинале управление не доходило
        ScriptedLlm llm = new ScriptedLlm().searches("Байкал").searches("Байкал").says("готово");

        call(new Recall(diaryWith(dir, "поездка на Байкал"), llm, "характер", NO_WEB).asTool(() -> ""), "что я знаю?");

        // второй поиск обязан сообщить, что всё уже выдано, а не «ничего не найдено»
        assertEquals(3, llm.chatCalls);
    }

    @Test
    void emptyDiaryAnswersHonestly(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm().searches("что угодно").says("Ничего не нашлось.");

        String answer = call(new Recall(new Diary(dir), llm, "характер", NO_WEB).asTool(() -> ""), "что я знаю про это?");

        assertEquals("Ничего не нашлось.", answer);
    }

    @Test
    void usedEntryGainsExperienceButStaysSearchable(@TempDir Path dir) {
        Diary diary = diaryWith(dir, "поездка на Байкал");
        ScriptedLlm llm = new ScriptedLlm().searches("Байкал").says("готово");

        call(new Recall(diary, llm, "характер", NO_WEB).asTool(() -> ""), "что я знаю про поездки?");

        Diary.Match match = diary.query(new double[] {1, 0, 0}).getFirst();
        assertEquals(1, match.entry().metadata().usageCount());
        assertTrue(match.entry().metadata().score() > 0);
    }
}
