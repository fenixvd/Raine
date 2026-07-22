package ru.rainedev.raine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveConfigTest {

    private static final String MINIMAL = """
            api_id=1
            api_hash=hash
            phone=+70000000000
            owner_id=1
            llm_base_url=http://example
            llm_model=model
            llm_api_key=key
            """;

    private static Path configIn(Path dir, String extra) throws IOException {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, MINIMAL + extra);
        return file;
    }

    @Test
    void editedSettingIsPickedUpWithoutRestart(@TempDir Path dir) throws IOException {
        Path file = configIn(dir, "randomly_go_sleep=true\n");
        List<Boolean> applied = new ArrayList<>();
        try (LiveConfig live = new LiveConfig(file, Config.load(file))) {
            live.onChange(fresh -> applied.add(fresh.behaviour().dayNaps()));
            assertEquals(List.of(true), applied, "настройки применяются сразу при подключении");

            Files.writeString(file, Files.readString(file).replace("randomly_go_sleep=true",
                    "randomly_go_sleep=false"));
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                    System.currentTimeMillis() + 2000));

            assertTrue(live.checkOnce(), "правка должна заметиться");
            assertEquals(List.of(true, false), applied);
            assertFalse(live.current().behaviour().dayNaps());
        }
    }

    @Test
    void untouchedFileChangesNothing(@TempDir Path dir) throws IOException {
        Path file = configIn(dir, "");
        try (LiveConfig live = new LiveConfig(file, Config.load(file))) {
            assertFalse(live.checkOnce(), "файл не трогали — перечитывать нечего");
        }
    }

    @Test
    void brokenFileLeavesPreviousSettingsInPlace(@TempDir Path dir) throws IOException {
        Path file = configIn(dir, "");
        try (LiveConfig live = new LiveConfig(file, Config.load(file))) {
            String before = live.current().llm().model();

            // остановиться из-за опечатки посреди разговора — худшее, что можно сделать
            Files.writeString(file, "api_id=1\n");
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                    System.currentTimeMillis() + 2000));

            assertFalse(live.checkOnce());
            assertEquals(before, live.current().llm().model());
        }
    }
}
