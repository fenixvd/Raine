package ru.rainedev.raine.speech;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Складывает готовые пакеты Opus в контейнер OGG — именно его требует Telegram
 * для голосовых сообщений.
 * <p>
 * Кодек берётся из библиотеки, а контейнер приходится собирать самим: внешних
 * программ в проекте нет сознательно, иначе бот молча терял бы голос при
 * переносе на машину без них.
 */
public final class OggOpusWriter {

    private static final byte[] MAGIC = "OggS".getBytes(StandardCharsets.US_ASCII);

    /** Позиция в потоке считается всегда в 48 кГц, независимо от частоты записи. */
    private static final int GRANULE_RATE = 48_000;

    private static final int MAX_SEGMENTS = 255;

    private static final int FLAG_CONTINUED = 0x01;
    private static final int FLAG_FIRST = 0x02;
    private static final int FLAG_LAST = 0x04;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final List<byte[]> pending = new ArrayList<>();

    private final int serial;
    private final int sampleRate;
    private final int channels;
    private final int preSkip;

    private int pageIndex;
    private int pendingSegments;
    private long granule;

    public OggOpusWriter(int sampleRate, int channels, int preSkip, int serial) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.preSkip = preSkip;
        this.serial = serial;
    }

    /** Заголовки: описание потока и пустой блок тегов. Оба обязательны. */
    public void writeHeaders() {
        writePage(opusHead(), FLAG_FIRST, 0);
        writePage(opusTags(), 0, 0);
    }

    /**
     * @param packet       закодированный пакет
     * @param samplesAt48k сколько звучания он добавляет, в отсчётах 48 кГц
     */
    public void writePacket(byte[] packet, int samplesAt48k) {
        int segments = packet.length / 255 + 1;
        if (pendingSegments + segments > MAX_SEGMENTS) {
            flushPage(false);
        }
        pending.add(packet);
        pendingSegments += segments;
        granule += samplesAt48k;
    }

    public byte[] finish() {
        flushPage(true);
        return out.toByteArray();
    }

    private void flushPage(boolean last) {
        if (pending.isEmpty()) {
            if (last) {
                // поток обязан закончиться страницей с признаком конца
                writePage(new byte[0], FLAG_LAST, granule);
            }
            return;
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        List<Integer> lacing = new ArrayList<>();
        for (byte[] packet : pending) {
            int remaining = packet.length;
            while (remaining >= 255) {
                lacing.add(255);
                remaining -= 255;
            }
            lacing.add(remaining);
            body.writeBytes(packet);
        }
        pending.clear();
        pendingSegments = 0;
        writePage(body.toByteArray(), lacing, last ? FLAG_LAST : 0, granule);
    }

    private void writePage(byte[] body, int flags, long granulePosition) {
        List<Integer> lacing = new ArrayList<>();
        int remaining = body.length;
        while (remaining >= 255) {
            lacing.add(255);
            remaining -= 255;
        }
        lacing.add(remaining);
        writePage(body, lacing, flags, granulePosition);
    }

    private void writePage(byte[] body, List<Integer> lacing, int flags, long granulePosition) {
        ByteBuffer page = ByteBuffer.allocate(27 + lacing.size() + body.length).order(ByteOrder.LITTLE_ENDIAN);
        page.put(MAGIC);
        page.put((byte) 0);                    // версия
        page.put((byte) flags);
        page.putLong(granulePosition);
        page.putInt(serial);
        page.putInt(pageIndex++);
        page.putInt(0);                        // место под контрольную сумму
        page.put((byte) lacing.size());
        for (int value : lacing) {
            page.put((byte) value);
        }
        page.put(body);

        byte[] bytes = page.array();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc(bytes));
        out.writeBytes(bytes);
    }

    private byte[] opusHead() {
        ByteBuffer head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        head.put("OpusHead".getBytes(StandardCharsets.US_ASCII));
        head.put((byte) 1);                    // версия формата
        head.put((byte) channels);
        head.putShort((short) preSkip);
        head.putInt(sampleRate);
        head.putShort((short) 0);              // усиление
        head.put((byte) 0);                    // раскладка каналов по умолчанию
        return head.array();
    }

    private static byte[] opusTags() {
        byte[] vendor = "raine".getBytes(StandardCharsets.UTF_8);
        ByteBuffer tags = ByteBuffer.allocate(8 + 4 + vendor.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        tags.put("OpusTags".getBytes(StandardCharsets.US_ASCII));
        tags.putInt(vendor.length);
        tags.put(vendor);
        tags.putInt(0);                        // комментариев нет
        return tags.array();
    }

    /** Контрольная сумма OGG: без отражения битов и без финального инвертирования. */
    private static int crc(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
        }
        return crc;
    }

    public static int granuleRate() {
        return GRANULE_RATE;
    }
}
