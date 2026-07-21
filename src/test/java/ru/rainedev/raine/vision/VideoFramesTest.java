package ru.rainedev.raine.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertTrue(VideoFrames.sample(notAVideo).isEmpty());
    }

    @Test
    void missingFileIsHandledQuietly(@TempDir Path dir) {
        assertTrue(VideoFrames.sample(dir.resolve("нет-такого.mp4")).isEmpty());
    }

    @Test
    void emptyFileIsHandledQuietly(@TempDir Path dir) throws IOException {
        Path empty = dir.resolve("пусто.mp4");
        Files.write(empty, new byte[0]);

        assertTrue(VideoFrames.sample(empty).isEmpty());
    }

    @Test
    void oneFramePerSecondButNoMoreThanSixteen() {
        assertEquals(1, VideoFrames.countFor(0.5));
        assertEquals(3, VideoFrames.countFor(2.4));
        assertEquals(16, VideoFrames.countFor(20));
        assertEquals(16, VideoFrames.countFor(600), "длинное видео всё равно укладывается в шестнадцать кадров");
    }

    @Test
    void durationIsUnknownForSomeFormats() {
        // тогда берём хотя бы один кадр, а не ноль
        assertEquals(1, VideoFrames.countFor(0));
        assertEquals(1, VideoFrames.countFor(-5));
    }

    @Test
    void timestampsLookLikeAPlayer() {
        assertEquals("0:00", VideoFrames.timestamp(0));
        assertEquals("0:07", VideoFrames.timestamp(6.8));
        assertEquals("1:23", VideoFrames.timestamp(83));
        assertEquals("10:00", VideoFrames.timestamp(600));
    }
}
