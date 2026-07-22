package ru.rainedev.raine.vision;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.scale.AWTUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Разбирает видео на кадры с отметками времени.
 * <p>
 * Кадр в секунду, но не больше шестнадцати на всё видео: реже — и движение
 * теряется, чаще — платим за одно и то же. Минутный ролик от этого сжимается,
 * но остаётся видно, что было в начале, а что в конце.
 */
public final class VideoFrames {

    private static final Logger log = LoggerFactory.getLogger(VideoFrames.class);

    /** Не чаще одного кадра в секунду. */
    private static final double MIN_STEP_SECONDS = 1.0;

    /** И не больше этого числа на всё видео, каким бы длинным оно ни было. */
    private static final int MAX_FRAMES = 16;

    /** Больше модели всё равно не нужно. */
    private static final int MAX_SIDE = 512;

    /**
     * Один кадр и промежуток, который он представляет.
     *
     * @param from секунда, на которой кадр снят
     * @param to   до какой секунды он «держится»
     */
    public record Frame(double from, double to, byte[] jpeg) {}

    private VideoFrames() {}

    /** @return кадры от начала к концу; пусто, если формат не поддался разбору */
    public static List<Frame> sample(Path video) {
        List<Frame> frames = new ArrayList<>();
        try (var channel = NIOUtils.readableChannel(video.toFile())) {
            FrameGrab grab = FrameGrab.createFrameGrab(channel);
            double duration = grab.getVideoTrack() == null || grab.getVideoTrack().getMeta() == null
                    ? 0.0 : grab.getVideoTrack().getMeta().getTotalDuration();
            int count = countFor(duration);

            for (int i = 0; i < count; i++) {
                double at = count > 1 ? duration * i / (count - 1) : 0;
                double until = count > 1 && i + 1 < count
                        ? duration * (i + 1) / (count - 1)
                        : at + MIN_STEP_SECONDS;
                grab.seekToSecondPrecise(at);
                BufferedImage frame = AWTUtil.toBufferedImage(grab.getNativeFrame());
                if (frame == null) {
                    log.debug("Кадр на {} с не декодировался", at);
                    continue;
                }
                frames.add(new Frame(at, until, toJpeg(fit(frame))));
            }
        } catch (Exception e) {
            // формат может оказаться неподдерживаемым — это не повод ломать разговор
            log.debug("Видео не разобралось: {}", e.getMessage());
        }
        return frames.isEmpty() ? viaFfmpeg(video) : frames;
    }

    /**
     * Запасной путь: свой декодер спотыкается на части форматов (например,
     * на чересстрочном h264), а ffmpeg, если он есть, разбирает почти всё.
     */
    private static List<Frame> viaFfmpeg(Path video) {
        double duration = VideoAudio.duration(video);
        int count = countFor(duration);
        List<Frame> frames = new ArrayList<>();
        for (Path file : VideoAudio.frames(video, count, duration)) {
            try {
                double at = count > 1 && duration > 0 ? duration * frames.size() / (count - 1) : 0;
                frames.add(new Frame(at, at + MIN_STEP_SECONDS, java.nio.file.Files.readAllBytes(file)));
            } catch (IOException e) {
                log.debug("Кадр не прочитался: {}", e.getMessage());
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // временный файл — не беда
                }
            }
        }
        if (!frames.isEmpty()) {
            log.debug("Кадры взяты через ffmpeg: {}", frames.size());
        }
        return frames;
    }

    static int countFor(double durationSeconds) {
        if (durationSeconds <= 0) {
            return 1;
        }
        return (int) Math.clamp((long) Math.ceil(durationSeconds / MIN_STEP_SECONDS), 1, MAX_FRAMES);
    }

    /** Отметка времени в привычном виде: 0:07, 1:23. */
    public static String timestamp(double seconds) {
        int whole = (int) Math.round(seconds);
        return "%d:%02d".formatted(whole / 60, whole % 60);
    }

    private static BufferedImage fit(BufferedImage frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        if (width <= MAX_SIDE && height <= MAX_SIDE) {
            return frame;
        }
        double scale = Math.min(MAX_SIDE / (double) width, MAX_SIDE / (double) height);
        int newWidth = Math.max(1, (int) (width * scale));
        int newHeight = Math.max(1, (int) (height * scale));
        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.drawImage(frame, 0, 0, newWidth, newHeight, null);
        graphics.dispose();
        return resized;
    }

    private static byte[] toJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
