package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiaryEnumerationTest {

    private static void writeEntry(Path dir, String id, String body) throws IOException {
        Files.writeString(dir.resolve(id + ".md"), """
                ---
                {"score":0,"confidence":0,"lastUsed":"never","usageCount":0,"embedding":[1.0,0.0,0.0]}
                ---

                %s
                """.formatted(body));
    }

    @Test
    void showingEverythingDoesNotRecomputeVectors(@TempDir Path dir) throws IOException {
        // так перебирают дневник для порыва и для пересмотра во сне: сравнивать
        // не с чем, а обращений к сети выходило по числу записей
        for (int i = 0; i < 5; i++) {
            writeEntry(dir, "100" + i, "запись номер " + i);
        }
        AtomicInteger asked = new AtomicInteger();
        Diary diary = new Diary(dir);
        diary.embedder(text -> {
            asked.incrementAndGet();
            return new double[] {1, 0, 0};
        });

        assertEquals(5, diary.query(new double[0]).size(), "показать должна всё");
        assertEquals(0, asked.get(), "и не спросить у сети ни разу");
    }

    @Test
    void searchStillFillsInMissingVectors(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("1000.md"), """
                ---
                {"score":0,"confidence":0,"lastUsed":"never","usageCount":0,"embedding":[]}
                ---

                запись, добавленная руками""");
        AtomicInteger asked = new AtomicInteger();
        Diary diary = new Diary(dir);
        diary.embedder(text -> {
            asked.incrementAndGet();
            return new double[] {1, 0, 0};
        });

        assertEquals(1.0, diary.query(new double[] {1, 0, 0}).getFirst().relatedness(), 1e-9);
        assertTrue(asked.get() > 0, "при настоящем поиске вектор досчитывается");
    }
}
