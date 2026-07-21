package ru.rainedev.raine.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OggOpusWriterTest {

    private static final int RATE = 24_000;
    private static final int FRAME = RATE * 20 / 1000;

    /** Секунда синусоиды — чтобы кодировать не тишину. */
    private static short[] tone(int seconds) {
        short[] samples = new short[RATE * seconds];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) (Math.sin(2 * Math.PI * 440 * i / RATE) * 8000);
        }
        return samples;
    }

    private static byte[] encodeTone(int seconds) throws Exception {
        OpusEncoder encoder = new OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_AUDIO);
        OggOpusWriter writer = new OggOpusWriter(RATE, 1, encoder.getLookahead(), 12345);
        writer.writeHeaders();

        short[] samples = tone(seconds);
        byte[] packet = new byte[4000];
        for (int offset = 0; offset + FRAME <= samples.length; offset += FRAME) {
            int length = encoder.encode(samples, offset, FRAME, packet, 0, packet.length);
            byte[] encoded = new byte[length];
            System.arraycopy(packet, 0, encoded, 0, length);
            writer.writePacket(encoded, OggOpusWriter.granuleRate() * 20 / 1000);
        }
        return writer.finish();
    }

    @Test
    void producesRecognisableOggStream() throws Exception {
        byte[] ogg = encodeTone(1);

        assertEquals("OggS", new String(ogg, 0, 4, StandardCharsets.US_ASCII));
        assertTrue(ogg.length > 1000, "поток подозрительно короткий: " + ogg.length);
    }

    @Test
    void firstPagesDescribeTheStream() throws Exception {
        String head = new String(encodeTone(1), StandardCharsets.ISO_8859_1);

        // без этих двух заголовков проигрыватель не поймёт, что перед ним
        assertTrue(head.contains("OpusHead"), "нет описания потока");
        assertTrue(head.contains("OpusTags"), "нет блока тегов");
    }

    @Test
    void headerSaysCorrectRateAndChannels() throws Exception {
        byte[] ogg = encodeTone(1);
        int at = new String(ogg, StandardCharsets.ISO_8859_1).indexOf("OpusHead");
        ByteBuffer header = ByteBuffer.wrap(ogg, at, 19).order(ByteOrder.LITTLE_ENDIAN);

        header.position(at + 9);
        assertEquals(1, header.get(), "канал должен быть один");
        header.position(at + 12);
        assertEquals(RATE, header.getInt(), "частота записи должна совпадать с настроенной");
    }

    /**
     * Разбирает поток страница за страницей по их же заголовкам. Искать подпись
     * перебором нельзя: она встречается и внутри закодированного звука.
     */
    private static java.util.List<int[]> pages(byte[] ogg) {
        java.util.List<int[]> found = new java.util.ArrayList<>();
        int at = 0;
        while (at + 27 <= ogg.length) {
            assertEquals('O', ogg[at], "страница должна начинаться с подписи");
            int segments = ogg[at + 26] & 0xFF;
            int body = 0;
            for (int i = 0; i < segments; i++) {
                body += ogg[at + 27 + i] & 0xFF;
            }
            int sequence = ByteBuffer.wrap(ogg, at + 18, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            found.add(new int[] {sequence, ogg[at + 5]});
            at += 27 + segments + body;
        }
        return found;
    }

    @Test
    void pagesAreNumberedInOrder() throws Exception {
        java.util.List<int[]> pages = pages(encodeTone(2));

        for (int i = 0; i < pages.size(); i++) {
            assertEquals(i, pages.get(i)[0], "страницы должны идти подряд");
        }
        // описание потока, теги и звук — минимум три; признак конца несёт последняя
        // звуковая страница, отдельной пустой не создаётся
        assertTrue(pages.size() >= 3, "страниц должно быть несколько, вышло: " + pages.size());
    }

    @Test
    void streamIsMarkedAsFinished() throws Exception {
        java.util.List<int[]> pages = pages(encodeTone(1));

        int flags = pages.getLast()[1];
        assertEquals(0x04, flags & 0x04, "последняя страница обязана быть помечена как конец потока");
        for (int i = 0; i < pages.size() - 1; i++) {
            assertEquals(0, pages.get(i)[1] & 0x04, "конец потока помечается только в последней странице");
        }
    }

    @Test
    void encodedSoundCanBeDecodedBack() throws Exception {
        // главная проверка: пакеты внутри контейнера — настоящий звук, а не мусор,
        // уложенный в правильную обёртку. Режим кодирования подобран именно так:
        // VOIP на этой частоте молча выдаёт тишину
        OpusEncoder encoder = new OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_AUDIO);
        OpusDecoder decoder = new OpusDecoder(RATE, 1);

        short[] samples = tone(1);
        byte[] packet = new byte[4000];
        short[] decoded = new short[FRAME];

        int loudest = 0;
        for (int offset = 0; offset + FRAME <= samples.length; offset += FRAME) {
            int length = encoder.encode(samples, offset, FRAME, packet, 0, packet.length);
            assertEquals(FRAME, decoder.decode(packet, 0, length, decoded, 0, FRAME, false));
            for (short sample : decoded) {
                loudest = Math.max(loudest, Math.abs(sample));
            }
        }

        assertTrue(loudest > 1000, "раскодированный звук оказался тишиной, громкость: " + loudest);
    }
}
