package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiaryTest {

    private static void writeEntry(Path dir, String id, String body, double[] vector) throws IOException {
        StringBuilder embedding = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            embedding.append(i == 0 ? "" : ",").append(vector[i]);
        }
        Files.writeString(dir.resolve(id + ".md"), """
                ---
                {"score":0,"confidence":0,"lastUsed":"never","usageCount":0,"embedding":[%s]}
                ---

                %s
                """.formatted(embedding, body));
    }

    @Test
    void readsExistingEntries(@TempDir Path dir) throws IOException {
        writeEntry(dir, "1000", "поездка на Байкал", new double[] {1, 0, 0});
        writeEntry(dir, "1001", "разговор про работу", new double[] {0, 1, 0});

        Diary diary = new Diary(dir);

        assertEquals(2, diary.size());
    }

    @Test
    void findsClosestEntryFirst(@TempDir Path dir) throws IOException {
        writeEntry(dir, "1000", "поездка на Байкал", new double[] {1, 0, 0});
        writeEntry(dir, "1001", "разговор про работу", new double[] {0, 1, 0});

        List<Diary.Match> matches = new Diary(dir).query(new double[] {0.9, 0.1, 0});

        assertEquals("1000", matches.getFirst().entry().id());
        assertTrue(matches.getFirst().relatedness() > matches.get(1).relatedness());
    }

    @Test
    void takenEntryDisappearsFromResults(@TempDir Path dir) throws IOException {
        // иначе одна и та же запись всплывала бы в контексте снова и снова
        writeEntry(dir, "1000", "поездка на Байкал", new double[] {1, 0, 0});
        Diary diary = new Diary(dir);

        diary.take(diary.query(new double[] {1, 0, 0}).getFirst());

        assertTrue(diary.query(new double[] {1, 0, 0}).isEmpty());
        assertTrue(Files.exists(dir.resolve("1000.md")), "с диска запись при этом не исчезает");
    }

    @Test
    void usingEntryRecordsExperience(@TempDir Path dir) throws IOException {
        writeEntry(dir, "1000", "поездка на Байкал", new double[] {1, 0, 0});
        Diary diary = new Diary(dir);

        DiaryEntry used = diary.take(diary.query(new double[] {1, 0, 0}).getFirst());

        assertEquals(1, used.metadata().usageCount());
        assertTrue(used.metadata().score() > 0, "запись пришлась к месту — оценка должна вырасти");
        assertNotEquals("never", used.metadata().lastUsed());

        // и это переживает перезагрузку
        assertEquals(1, new Diary(dir).query(new double[] {1, 0, 0}).getFirst().entry().metadata().usageCount());
    }

    @Test
    void weakMatchLowersScore(@TempDir Path dir) throws IOException {
        writeEntry(dir, "1000", "не в тему", new double[] {1, 0, 0});
        Diary diary = new Diary(dir);

        DiaryEntry used = diary.take(new Diary.Match(diary.query(new double[] {1, 0, 0}).getFirst().entry(), 0.1));

        assertTrue(used.metadata().score() < 0, "притянутая за уши запись должна терять в оценке");
    }

    @Test
    void savedEntryIsFoundAfterRestart(@TempDir Path dir) {
        Diary diary = new Diary(dir);

        diary.save("сегодня был долгий вечер", new double[] {0, 0, 1});

        Diary reopened = new Diary(dir);
        assertEquals(1, reopened.size());
        assertEquals("сегодня был долгий вечер", reopened.query(new double[] {0, 0, 1}).getFirst().entry().body());
    }

    @Test
    void brokenEntryDoesNotBreakStartup(@TempDir Path dir) throws IOException {
        writeEntry(dir, "1000", "нормальная запись", new double[] {1, 0, 0});
        Files.writeString(dir.resolve("1001.md"), "какой-то мусор без заголовка");

        Diary diary = new Diary(dir);

        assertEquals(1, diary.size(), "битая запись пропускается, остальные читаются");
    }

    @Test
    void relatednessIsNormalised() {
        // все пороги в системе — доля от нуля до единицы, а не сырой косинус
        assertEquals(1.0, Similarity.cosine(new double[] {1, 0}, new double[] {1, 0}), 1e-9);
        assertEquals(0.5, Similarity.cosine(new double[] {1, 0}, new double[] {0, 1}), 1e-9);
        assertEquals(0.0, Similarity.cosine(new double[] {1, 0}, new double[] {-1, 0}), 1e-9);
    }

    @Test
    void entryWithoutVectorGetsOneOnFirstSearch(@TempDir Path dir) throws IOException {
        // иначе запись, добавленная вручную, осталась бы невидимой навсегда
        Files.writeString(dir.resolve("1000.md"), """
                ---
                {"score":0,"confidence":0,"lastUsed":"never","usageCount":0,"embedding":[]}
                ---

                запись, добавленная руками""");
        Diary diary = new Diary(dir);
        diary.embedder(text -> new double[] {1, 0, 0});

        List<Diary.Match> matches = diary.query(new double[] {1, 0, 0});

        assertEquals(1.0, matches.getFirst().relatedness(), 1e-9);
        assertTrue(Files.readString(dir.resolve("1000.md")).contains("1.0"), "вектор должен сохраниться");
    }

    @Test
    void vectorsOfDifferentLengthAreNotRelated() {
        assertEquals(0, Similarity.cosine(new double[] {1, 0}, new double[] {1, 0, 0}));
    }

    @Test
    void identicalVectorsAreFullyRelated() {
        assertEquals(1.0, Similarity.cosine(new double[] {0.5, 0.5}, new double[] {0.5, 0.5}), 1e-9);
    }

    @Test
    void emptyDirectoryIsNotAnError(@TempDir Path dir) {
        assertTrue(new Diary(dir.resolve("нет-такого")).isEmpty());
        assertFalse(new Diary(dir).size() > 0);
    }
}
