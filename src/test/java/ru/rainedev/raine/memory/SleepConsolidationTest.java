package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

class SleepConsolidationTest {

    private static final class ScriptedLlm implements LlmClient {
        private final Deque<String> answers = new ArrayDeque<>();
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

        @Override
        public double[] embedding(String input) {
            return new double[] {1, 0, 0};
        }
    }

    private SleepConsolidation consolidation(Diary diary, ScriptedLlm llm, Path archive) {
        return new SleepConsolidation(diary, llm, "перепиши", archive, 4000, new Random(1));
    }

    @Test
    void mergedEntriesReplaceTheOldOnes(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("Ездили на Байкал в июле, было холодно", new double[] {1, 0, 0});
        diary.save("Байкал запомнился прозрачной водой", new double[] {1, 0, 0});

        ScriptedLlm llm = new ScriptedLlm()
                .willAnswer("{\"confidence\":0.5}\nПоездка на Байкал в июле: холодно, вода прозрачная.");

        consolidation(diary, llm, dir.resolve("archive")).run(Duration.ofMinutes(5), () -> false);

        Diary after = new Diary(dir);
        assertEquals(1, after.size(), "две близкие записи должны стать одной");
        assertTrue(after.query(new double[] {1, 0, 0}).getFirst().entry().body().contains("прозрачная"));
    }

    @Test
    void oldEntriesGoToArchiveNotToOblivion(@TempDir Path dir) throws IOException {
        // алгоритм переписывает память необратимо — возможность вернуться дороже места
        Diary diary = new Diary(dir);
        diary.save("Что-то, что перепишут", new double[] {1, 0, 0});

        Path archive = dir.resolve("archive");
        consolidation(diary, new ScriptedLlm().willAnswer("{\"confidence\":0.5}\nПереписанная запись."), archive)
                .run(Duration.ofMinutes(5), () -> false);

        assertTrue(Files.isDirectory(archive), "архив должен появиться");
        try (var files = Files.list(archive)) {
            assertEquals(1, files.count(), "прежняя запись обязана сохраниться в архиве");
        }
    }

    @Test
    void establishedFactsAreNeverRewritten(@TempDir Path dir) throws IOException {
        Diary diary = new Diary(dir);
        diary.save("Установленный факт", new double[] {1, 0, 0});
        // помечаем как факт вручную: такие записи алгоритм трогать не должен
        Path file;
        try (var files = Files.list(dir)) {
            file = files.filter(p -> p.toString().endsWith(".md")).findFirst().orElseThrow();
        }
        Files.writeString(file, Files.readString(file).replace("\"confidence\":0.0", "\"confidence\":1.0"));

        consolidation(new Diary(dir), new ScriptedLlm().willAnswer("{\"confidence\":0.5}\nПопытка переписать факт."),
                dir.resolve("archive")).run(Duration.ofMinutes(5), () -> false);

        assertTrue(Files.exists(file), "факт должен остаться на месте");
    }

    @Test
    void forgottenEntriesAreNotStored(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("Незначительная мелочь", new double[] {1, 0, 0});

        consolidation(diary, new ScriptedLlm().willAnswer("{\"confidence\":-1.0}\nэто стоит забыть"),
                dir.resolve("archive")).run(Duration.ofMinutes(5), () -> false);

        assertEquals(0, new Diary(dir).size(), "помеченное к забвению не сохраняется");
    }

    @Test
    void wakingUpStopsTheWork(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        for (int i = 0; i < 5; i++) {
            diary.save("Запись номер " + i, new double[] {i, 1, 0});
        }
        ScriptedLlm llm = new ScriptedLlm();

        consolidation(diary, llm, dir.resolve("archive")).run(Duration.ofMinutes(5), () -> true);

        assertEquals(0, llm.chatCalls, "если её позвали, память не пересматривается");
    }

    @Test
    void garbageFromModelIsNotStored(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("Настоящая запись", new double[] {1, 0, 0});

        consolidation(diary, new ScriptedLlm()
                        .willAnswer("<｜｜DSML｜｜tool_calls>")
                        .willAnswer("<｜｜DSML｜｜tool_calls>")
                        .willAnswer("<｜｜DSML｜｜tool_calls>"),
                dir.resolve("archive")).run(Duration.ofMinutes(5), () -> false);

        Diary after = new Diary(dir);
        assertEquals(1, after.size(), "прежняя запись остаётся на месте");
        assertFalse(after.query(new double[] {1, 0, 0}).getFirst().entry().body().contains("DSML"));
    }

    @Test
    void emptyDiaryIsNotAProblem(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm();

        consolidation(new Diary(dir), llm, dir.resolve("archive")).run(Duration.ofMinutes(5), () -> false);

        assertEquals(0, llm.chatCalls);
    }
}
