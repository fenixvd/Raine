package ru.rainedev.raine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {

    private static Path fileWith(Path dir, String contents) throws IOException {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, contents);
        return file;
    }

    @Test
    void missingSettingsAreAppendedWithTheirExplanation(@TempDir Path dir) throws IOException {
        // иначе о новых настройках можно узнать только из исходников
        Path file = fileWith(dir, "known=уже задано\n");
        Settings settings = Settings.read(file);
        settings.get("known", "по умолчанию", "давно известная");
        settings.get("fresh", "новое", "появилась в этой версии");

        settings.sync();

        String written = Files.readString(file);
        assertTrue(written.contains("# появилась в этой версии"), written);
        assertTrue(written.contains("fresh=новое"), written);
        assertFalse(written.contains("known=по умолчанию"), "уже заданное не переписывается: " + written);
        assertEquals("уже задано", Settings.read(file).get("known", "по умолчанию", "давно известная"));
    }

    @Test
    void settingsAreReadableAfterBeingAppended(@TempDir Path dir) throws IOException {
        Path file = fileWith(dir, "known=1\n");
        Settings settings = Settings.read(file);
        settings.get("fresh", "значение", "пояснение");
        settings.sync();

        assertEquals("значение", Settings.read(file).get("fresh", "другое", "пояснение"));
    }

    @Test
    void requiredSettingSaysWhereToGetIt(@TempDir Path dir) throws IOException {
        Settings settings = Settings.read(fileWith(dir, ""));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> settings.require("api_hash", "ключ приложения с my.telegram.org"));

        assertTrue(failure.getMessage().contains("my.telegram.org"), failure.getMessage());
        assertTrue(failure.getMessage().contains("RAINE_API_HASH"), failure.getMessage());
    }

    @Test
    void numberInsteadOfWordIsRejectedAtStartup(@TempDir Path dir) throws IOException {
        Settings settings = Settings.read(fileWith(dir, "delay=скоро\n"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> settings.integer("delay", 30, "задержка"));

        assertTrue(failure.getMessage().contains("delay"), failure.getMessage());
    }

    @Test
    void valuesAreReadInTheirOwnTypes(@TempDir Path dir) throws IOException {
        Settings settings = Settings.read(fileWith(dir,
                "count=7\nshare=0.25\nflag=true\nwhen=23:30\nwhere=data/diary\n"));

        assertEquals(7, settings.integer("count", 0, ""));
        assertEquals(0.25, settings.fraction("share", 0, ""));
        assertTrue(settings.flag("flag", false, ""));
        assertEquals(java.time.LocalTime.of(23, 30), settings.time("when", "00:00", ""));
        assertEquals(Path.of("data/diary"), settings.path("where", "", ""));
    }

    @Test
    void nothingIsWrittenWhenEverythingIsInPlace(@TempDir Path dir) throws IOException {
        Path file = fileWith(dir, "known=1\n");
        Settings settings = Settings.read(file);
        settings.get("known", "1", "давно известная");

        settings.sync();

        assertEquals("known=1\n", Files.readString(file));
    }
}
