package ru.rainedev.raine.speech;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Мелкая работа с сырым звуком: развернуть байты в отсчёты, свести каналы
 * в один и сменить частоту.
 * <p>
 * Нужно это там, где звук пришёл чужой — из видео он приходит стерео и на
 * своей частоте, а кодеку нужен моно-поток на одной из тех частот, которые
 * он знает.
 */
public final class Pcm {

    /**
     * Сколько периодов среза укладывается в половину окна. Шире — чище срез
     * и дольше счёт; на речи дальше этого разницы уже не слышно.
     */
    private static final int WINDOW = 16;

    /** На столько долей делится расстояние между соседними отсчётами. */
    private static final int PHASES = 512;

    private Pcm() {}

    /** Байты 16 бит со знаком — в отсчёты. */
    public static short[] fromBytes(byte[] pcm, ByteOrder order) {
        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(order);
        short[] samples = new short[pcm.length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buffer.getShort();
        }
        return samples;
    }

    /** Каналы идут вперемежку — усредняем их в один. */
    public static short[] mono(short[] interleaved, int channels) {
        if (channels <= 1) {
            return interleaved;
        }
        short[] mono = new short[interleaved.length / channels];
        for (int i = 0; i < mono.length; i++) {
            int sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                sum += interleaved[i * channels + channel];
            }
            mono[i] = (short) (sum / channels);
        }
        return mono;
    }

    /**
     * Меняет частоту дискретизации.
     * <p>
     * Считается по окну соседей, а не по паре ближайших: при понижении частоты
     * простая интерполяция заворачивает высокие частоты в слышимый диапазон,
     * и речь начинает звенеть — распознаванию от такого только хуже.
     */
    public static short[] resample(short[] samples, int fromRate, int toRate) {
        if (fromRate == toRate || samples.length == 0) {
            return samples;
        }
        double step = (double) fromRate / toRate;
        // при понижении частоты срезаем всё, чего новая частота уже не вмещает
        double cutoff = Math.min(1.0, 1.0 / step);
        // чем ниже срез, тем шире должно быть окно: узкое окно срезает нечисто,
        // и обрезанные высокие частоты возвращаются звоном
        int half = (int) Math.ceil(WINDOW / cutoff);
        int taps = 2 * half;
        double[][] weights = weights(cutoff, half);
        int length = (int) (samples.length / step);
        short[] out = new short[Math.max(1, length)];
        int last = samples.length - 1;

        for (int i = 0; i < out.length; i++) {
            double center = i * step;
            int base = (int) center;
            double[] row = weights[(int) ((center - base) * PHASES)];
            int first = base - half + 1;
            double sum = 0;

            for (int k = 0; k < taps; k++) {
                sum += row[k] * samples[Math.clamp(first + k, 0, last)];
            }
            out[i] = (short) Math.clamp(Math.round(sum), Short.MIN_VALUE, Short.MAX_VALUE);
        }
        return out;
    }

    /**
     * Коэффициенты фильтра, посчитанные один раз на все отсчёты.
     * <p>
     * Считать их на лету — значит звать синус и косинус по три раза на каждый
     * отсчёт окна: минутная дорожка так пересчитывалась восемнадцать секунд.
     * Дробная часть позиции округляется до одной из {@value #PHASES} долей —
     * ошибка выходит меньше тысячной отсчёта, её не слышно.
     */
    private static double[][] weights(double cutoff, int half) {
        double[][] rows = new double[PHASES][2 * half];
        for (int phase = 0; phase < PHASES; phase++) {
            double fraction = (double) phase / PHASES;
            double sum = 0;
            for (int k = 0; k < rows[phase].length; k++) {
                double distance = fraction + half - 1 - k;
                double weight = sinc(cutoff * distance) * window(distance, half);
                rows[phase][k] = weight;
                sum += weight;
            }
            // громкость не должна зависеть от того, куда попал отсчёт
            if (sum != 0) {
                for (int k = 0; k < rows[phase].length; k++) {
                    rows[phase][k] /= sum;
                }
            }
        }
        return rows;
    }

    private static double sinc(double x) {
        if (x == 0) {
            return 1;
        }
        double pi = Math.PI * x;
        return Math.sin(pi) / pi;
    }

    /** Окно Блэкмана: без него края окна дают призвуки. */
    private static double window(double distance, int half) {
        if (Math.abs(distance) >= half) {
            return 0;
        }
        double x = (distance + half) / (2.0 * half);
        return 0.42 - 0.5 * Math.cos(2 * Math.PI * x) + 0.08 * Math.cos(4 * Math.PI * x);
    }
}
