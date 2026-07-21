package ru.rainedev.raine.prompt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.config.Config;

class PromptsTest {

    private static final Config.Character CHARACTER = new Config.Character("Raine", "@raine_tyan", "RaineDev");

    @Test
    void substitutesPlaceholders(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.md"), "Тебя зовут ${CHARACTER_NAME}, владелец — ${PAPIK_NAME}.");

        String result = new Prompts(dir, CHARACTER).load("a.md");

        assertTrue(result.contains("Тебя зовут Raine, владелец — RaineDev."));
    }

    @Test
    void stripsHumanReadableHeader(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.md"), """
                ---
                Комментарий для человека, модели он не нужен.
                ---

                Полезный текст.""");

        String result = new Prompts(dir, CHARACTER).load("a.md");

        assertFalse(result.contains("Комментарий для человека"));
        assertTrue(result.startsWith("Полезный текст."));
    }

    @Test
    void failsLoudlyOnUnknownPlaceholder(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.md"), "Привет, ${SOMETHING_ELSE}!");

        Prompts prompts = new Prompts(dir, CHARACTER);

        // молча оставить ${...} в промпте опаснее, чем упасть: модель увидит мусор
        assertThrows(IllegalStateException.class, () -> prompts.load("a.md"));
    }

    @Test
    void assemblesRealSystemPrompt() {
        Prompts prompts = new Prompts(Path.of("prompts"), CHARACTER);

        String system = prompts.system("");

        assertFalse(system.contains("${"), "в собранном промпте не должно остаться плейсхолдеров");
        assertTrue(system.contains("Raine"));
        assertTrue(system.length() > 10_000, "системный промпт подозрительно короткий");
    }
}
