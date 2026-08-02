package ru.rainedev.raine.speech;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import java.util.Random;

/**
 * Превращает сырой звук в OGG/Opus — формат, который Telegram принимает
 * как голосовое, а распознавание речи понимает без уговоров.
 * <p>
 * Кодек и контейнер целиком свои: {@link OggOpusWriter} собирает страницы,
 * а кодирует чистый Java-порт libopus. Внешних программ нет сознательно —
 * иначе бот молча терял бы голос при переносе на машину без них.
 */
public final class OggOpus {

    /** Кадр в 20 мс — обычный для речи размер. */
    private static final int FRAME_MILLIS = 20;

    private static final int MAX_PACKET = 4000;

    private static final Random RANDOM = new Random();

    private OggOpus() {}

    /**
     * @param samples    отсчёты 16 бит, один канал
     * @param sampleRate частота; кодек принимает 8, 12, 16, 24 или 48 кГц
     * @param bitrate    сколько бит в секунду тратить
     */
    public static byte[] encode(short[] samples, int sampleRate, int bitrate) {
        int channels = 1;
        int frameSamples = sampleRate * FRAME_MILLIS / 1000;

        try {
            // Режим VOIP выглядит уместнее для речи, но в этой сборке кодека он на
            // 24 кГц выдаёт тишину: пакеты формируются, длина правдоподобная, звука нет.
            // Проверено раскодированием — см. OggOpusWriterTest.
            OpusEncoder encoder = new OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_AUDIO);
            encoder.setBitrate(bitrate);

            OggOpusWriter ogg = new OggOpusWriter(sampleRate, channels, encoder.getLookahead(), RANDOM.nextInt());
            ogg.writeHeaders();

            byte[] packet = new byte[MAX_PACKET];
            int samplesPerFrameAt48k = OggOpusWriter.granuleRate() * FRAME_MILLIS / 1000;

            for (int offset = 0; offset < samples.length; offset += frameSamples) {
                short[] frame = new short[frameSamples];
                int available = Math.min(frameSamples, samples.length - offset);
                System.arraycopy(samples, offset, frame, 0, available);
                // последний кадр добивается тишиной: кодек принимает только целые кадры

                int length = encoder.encode(frame, 0, frameSamples, packet, 0, packet.length);
                byte[] encoded = new byte[length];
                System.arraycopy(packet, 0, encoded, 0, length);
                ogg.writePacket(encoded, samplesPerFrameAt48k);
            }
            return ogg.finish();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось закодировать звук: " + e.getMessage(), e);
        }
    }
}
