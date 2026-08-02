package ru.rainedev.raine.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class PcmTest {

    private static short[] tone(int rate, int hertz, double seconds) {
        short[] samples = new short[(int) (rate * seconds)];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) (Math.sin(2 * Math.PI * hertz * i / rate) * 8000);
        }
        return samples;
    }

    /** Частота на слух: считаем, сколько раз волна перешла через ноль. */
    private static double hertzOf(short[] samples, int rate) {
        int crossings = 0;
        for (int i = 1; i < samples.length; i++) {
            if (samples[i - 1] < 0 && samples[i] >= 0) {
                crossings++;
            }
        }
        return crossings / (samples.length / (double) rate);
    }

    @Test
    void bytesBecomeSamples() {
        byte[] bytes = {0x00, 0x01, (byte) 0xFF, (byte) 0xFF};

        short[] samples = Pcm.fromBytes(bytes, ByteOrder.LITTLE_ENDIAN);

        assertEquals(2, samples.length);
        assertEquals(256, samples[0]);
        assertEquals(-1, samples[1]);
    }

    @Test
    void channelsAreAveragedIntoOne() {
        short[] stereo = {100, 300, -50, -150};

        short[] mono = Pcm.mono(stereo, 2);

        assertEquals(2, mono.length);
        assertEquals(200, mono[0]);
        assertEquals(-100, mono[1]);
    }

    @Test
    void oneChannelIsLeftAsIs() {
        short[] samples = {1, 2, 3};

        assertEquals(samples, Pcm.mono(samples, 1));
    }

    @Test
    void resamplingKeepsTheTone() {
        short[] source = tone(44_100, 440, 1);

        short[] resampled = Pcm.resample(source, 44_100, 16_000);

        assertEquals(16_000, resampled.length, 2, "длина должна соответствовать новой частоте");
        assertEquals(440, hertzOf(resampled, 16_000), 5, "тон не должен уехать");
    }

    @Test
    void resamplingUpwardsKeepsTheTone() {
        short[] source = tone(16_000, 440, 1);

        short[] resampled = Pcm.resample(source, 16_000, 48_000);

        assertEquals(48_000, resampled.length, 2);
        assertEquals(440, hertzOf(resampled, 48_000), 5);
    }

    @Test
    void resamplingKeepsLoudness() {
        short[] source = tone(44_100, 440, 1);

        short[] resampled = Pcm.resample(source, 44_100, 16_000);

        int loudest = 0;
        for (short sample : resampled) {
            loudest = Math.max(loudest, Math.abs(sample));
        }
        assertTrue(loudest > 7000 && loudest < 9000, "громкость уехала: " + loudest);
    }

    /**
     * Высокий тон новая частота уже не вмещает. Простая интерполяция завернула бы
     * его в слышимый диапазон — вышел бы звон вместо тишины, и распознавание
     * услышало бы то, чего не было.
     */
    @Test
    void tooHighToneIsCutOffRatherThanFolded() {
        short[] source = tone(44_100, 7000, 1);

        short[] resampled = Pcm.resample(source, 44_100, 8_000);

        // края не в счёт: там окно свисает за пределы записи и даёт щелчок
        // на старте и в конце — на слух это никак, а на замер влияет
        int loudest = 0;
        for (int i = 200; i < resampled.length - 200; i++) {
            loudest = Math.max(loudest, Math.abs(resampled[i]));
        }
        assertTrue(loudest < 200, "срезанный тон вернулся призраком, громкость: " + loudest);
    }

    @Test
    void sameRateChangesNothing() {
        short[] samples = tone(16_000, 440, 0.1);

        assertEquals(samples, Pcm.resample(samples, 16_000, 16_000));
    }

    @Test
    void emptySoundSurvives() {
        assertEquals(0, Pcm.resample(new short[0], 44_100, 16_000).length);
    }
}
