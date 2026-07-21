package ru.rainedev.raine.vision;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoFramesTest {

    @Test
    void unsupportedFileDoesNotBreakTheConversation(@TempDir Path dir) throws IOException {
        // формат может оказаться незнакомым — это не повод ронять разбор сообщения
        Path notAVideo = dir.resolve("письмо.mp4");
        Files.writeString(notAVideo, "это вообще не видео");

        assertTrue(VideoFrames.filmstrip(notAVideo).isEmpty());
    }

    @Test
    void missingFileIsHandledQuietly(@TempDir Path dir) {
        assertTrue(VideoFrames.filmstrip(dir.resolve("нет-такого.mp4")).isEmpty());
    }

    @Test
    void emptyFileIsHandledQuietly(@TempDir Path dir) throws IOException {
        Path empty = dir.resolve("пусто.mp4");
        Files.write(empty, new byte[0]);

        assertFalse(VideoFrames.filmstrip(empty).isPresent());
    }
}
