package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.memory.Diary;

class SpontaneityTest {

    private static final LlmClient UNUSED = new LlmClient() {
        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {1};
        }
    };

    private static NotificationLoop loop() {
        return new NotificationLoop(UNUSED, () -> "промпт", new Toolbox(), 40_000);
    }

    @Test
    void asleepSheHasNoImpulses(@TempDir Path dir) {
        // спящему человеку ничего не приходит в голову: ни разговоров,
        // ни записей в память, ни строчек в журнале
        Diary diary = new Diary(dir);
        diary.save("что-то из прошлого", new double[] {1, 0, 0});
        NotificationLoop loop = loop();

        try (Spontaneity spontaneity = new Spontaneity(loop, diary, new Toolbox(), new Random(1))) {
            spontaneity.asleepWhen(() -> true);
            spontaneity.actNow();

            assertEquals(0, loop.queued(), "во сне порывов быть не должно");
        }
    }

    @Test
    void awakeSheSometimesWritesFirst(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("что-то из прошлого", new double[] {1, 0, 0});
        NotificationLoop loop = loop();

        try (Spontaneity spontaneity = new Spontaneity(loop, diary, new Toolbox(), new Random(1))) {
            int impulses = 0;
            for (int attempt = 0; attempt < 40; attempt++) {
                spontaneity.actNow();
                impulses = loop.queued();
                if (impulses > 0) {
                    break;
                }
            }
            assertTrue(impulses > 0, "бодрствуя, она хоть иногда пишет сама");
        }
    }
}
