package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Проверяет, что накопленный дневник читается без переноса и пересчёта.
 * Запуск: ./gradlew integrationTest -Draine.diary=/путь/к/diary
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "raine.diary", matches = ".+")
class RealDiaryCompatibilityTest {

    private final Path dir = Path.of(System.getProperty("raine.diary", ""));

    @Test
    void readsRealDiaryWithoutLosingEntries() throws Exception {
        long onDisk;
        try (var files = Files.list(dir)) {
            onDisk = files.filter(path -> path.toString().endsWith(".md")).count();
        }

        Diary diary = new Diary(dir);

        assertEquals(onDisk, diary.size(), "все записи должны прочитаться");
        assertTrue(onDisk > 0, "каталог не должен быть пустым");
    }

    @Test
    void searchOverRealEntriesReturnsSaneNumbers() {
        Diary diary = new Diary(dir);
        DiaryEntry any = diary.query(new double[3072]).getFirst().entry();

        // запись, найденная сама по себе, обязана совпасть почти идеально
        List<Diary.Match> matches = diary.query(any.embedding());

        assertEquals(any.id(), matches.getFirst().entry().id());
        assertTrue(matches.getFirst().relatedness() > 0.99,
                "близость к самой себе: " + matches.getFirst().relatedness());
        assertTrue(matches.getLast().relatedness() < matches.getFirst().relatedness());
        assertFalse(any.body().contains("DSML"), "в теле не должно быть служебной разметки");
    }
}
