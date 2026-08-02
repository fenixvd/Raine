package ru.rainedev.raine.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jaredmdobson.concentus.OpusDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Образец рядом — двухсекундный ролик с тоном 440 Гц (AAC, 44,1 кГц, стерео),
 * то есть ровно та связка, которая приходит из переписки. Проверяется весь путь
 * целиком: разбор MP4, декодирование AAC, сведение каналов, смена частоты
 * и кодирование — и всё это без единой внешней программы.
 */
class VideoAudioTest {

    /** На эту частоту дорожка пересчитывается. */
    private static final int RATE = 16_000;

    private static Path fixture() throws IOException {
        Path video = Files.createTempFile("raine-test-video-", ".mp4");
        try (InputStream source = VideoAudioTest.class.getResourceAsStream("/tone.mp4")) {
            assertTrue(source != null, "образец видео не найден");
            Files.write(video, source.readAllBytes());
        }
        return video;
    }

    /** Пакеты Opus из контейнера: страницы читаются по их же заголовкам. */
    private static List<byte[]> packets(byte[] ogg) {
        List<byte[]> packets = new ArrayList<>();
        java.io.ByteArrayOutputStream current = new java.io.ByteArrayOutputStream();
        int at = 0;
        while (at + 27 <= ogg.length) {
            assertEquals("OggS", new String(ogg, at, 4, StandardCharsets.US_ASCII));
            int segments = ogg[at + 26] & 0xFF;
            int body = at + 27 + segments;
            for (int i = 0; i < segments; i++) {
                int length = ogg[at + 27 + i] & 0xFF;
                current.write(ogg, body, length);
                body += length;
                if (length < 255) {
                    packets.add(current.toByteArray());
                    current.reset();
                }
            }
            at = body;
        }
        return packets;
    }

    private static short[] decode(byte[] ogg) throws Exception {
        OpusDecoder decoder = new OpusDecoder(RATE, 1);
        List<byte[]> packets = packets(ogg);
        // первые два пакета — описание потока и теги, звука в них нет
        assertTrue(packets.size() > 2, "в потоке нет звуковых пакетов");

        java.io.ByteArrayOutputStream sound = new java.io.ByteArrayOutputStream();
        short[] frame = new short[RATE * 20 / 1000];
        for (byte[] packet : packets.subList(2, packets.size())) {
            int decoded = decoder.decode(packet, 0, packet.length, frame, 0, frame.length, false);
            for (int i = 0; i < decoded; i++) {
                sound.write(frame[i] & 0xFF);
                sound.write(frame[i] >> 8 & 0xFF);
            }
        }
        byte[] bytes = sound.toByteArray();
        short[] samples = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        return samples;
    }

    @Test
    void videoSoundBecomesOggOpus() throws IOException {
        Path video = fixture();
        try {
            Optional<Path> audio = VideoAudio.extract(video);

            assertTrue(audio.isPresent(), "звук из видео не вынулся");
            try {
                byte[] ogg = Files.readAllBytes(audio.get());
                String head = new String(ogg, StandardCharsets.ISO_8859_1);
                assertEquals("OggS", head.substring(0, 4));
                assertTrue(head.contains("OpusHead"), "нет описания потока");
                assertTrue(head.contains("OpusTags"), "нет блока тегов");
                assertTrue(ogg.length > 1000, "поток подозрительно короткий: " + ogg.length);
            } finally {
                Files.deleteIfExists(audio.get());
            }
        } finally {
            Files.deleteIfExists(video);
        }
    }

    @Test
    void theSoundItselfSurvivesTheWholeWay() throws Exception {
        Path video = fixture();
        Path audio = VideoAudio.extract(video).orElseThrow();
        try {
            short[] samples = decode(Files.readAllBytes(audio));

            int loudest = 0;
            int crossings = 0;
            for (int i = 1; i < samples.length; i++) {
                loudest = Math.max(loudest, Math.abs(samples[i]));
                if (samples[i - 1] < 0 && samples[i] >= 0) {
                    crossings++;
                }
            }
            double seconds = samples.length / (double) RATE;
            assertEquals(2, seconds, 0.2, "дорожка должна быть длиной с ролик");
            assertTrue(loudest > 1000, "вместо звука вышла тишина, громкость: " + loudest);
            assertEquals(440, crossings / seconds, 15, "тон уехал — где-то перепутана частота");
        } finally {
            Files.deleteIfExists(audio);
            Files.deleteIfExists(video);
        }
    }

    @Test
    void headerSaysOneChannelAtSpeechRate() throws IOException {
        Path video = fixture();
        Path audio = VideoAudio.extract(video).orElseThrow();
        try {
            byte[] ogg = Files.readAllBytes(audio);
            int at = new String(ogg, StandardCharsets.ISO_8859_1).indexOf("OpusHead");
            ByteBuffer header = ByteBuffer.wrap(ogg).order(ByteOrder.LITTLE_ENDIAN);

            assertEquals(1, header.get(at + 9), "каналы должны быть сведены в один");
            assertEquals(RATE, header.getInt(at + 12), "частота должна быть речевой");
        } finally {
            Files.deleteIfExists(audio);
            Files.deleteIfExists(video);
        }
    }

    @Test
    void nonsenseFileIsNotAnError() throws IOException {
        Path file = Files.createTempFile("raine-test-", ".mp4");
        try {
            Files.write(file, "это не видео".getBytes(StandardCharsets.UTF_8));

            assertFalse(VideoAudio.extract(file).isPresent(), "из мусора не должно получиться дорожки");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void missingFileIsNotAnError() {
        assertFalse(VideoAudio.extract(Path.of("/net/takogo/video.mp4")).isPresent());
    }
}
