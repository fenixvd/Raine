package ru.rainedev.raine.vision;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.jcodec.api.FrameGrab;
import org.jcodec.scale.AWTUtil;
import org.jcodec.common.io.NIOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Достаёт из видео несколько кадров и склеивает их в одну картинку.
 * <p>
 * Склейка — не экономия ради экономии: по трём кадрам подряд видно движение
 * и развитие, а описывать каждый отдельно означало бы три обращения к модели
 * вместо одного и потерю связи между ними.
 */
public final class VideoFrames {

    private static final Logger log = LoggerFactory.getLogger(VideoFrames.class);

    private static final int FRAME_COUNT = 3;

    /** Шире делать незачем: модель всё равно смотрит на уменьшенное. */
    private static final int TILE_WIDTH = 512;

    private VideoFrames() {}

    /** @return склеенная лента кадров или пусто, если видео не поддалось */
    public static Optional<byte[]> filmstrip(Path video) {
        List<BufferedImage> frames = grab(video);
        if (frames.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(toJpeg(tile(frames)));
        } catch (IOException e) {
            log.debug("Кадры не склеились: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static List<BufferedImage> grab(Path video) {
        List<BufferedImage> frames = new ArrayList<>();
        try (var channel = NIOUtils.readableChannel(video.toFile())) {
            FrameGrab grab = FrameGrab.createFrameGrab(channel);
            double duration = grab.getVideoTrack() == null || grab.getVideoTrack().getMeta() == null
                    ? 0.0 : grab.getVideoTrack().getMeta().getTotalDuration();

            for (int i = 0; i < FRAME_COUNT; i++) {
                // кадры берём с равными промежутками: начало, середина, конец
                double at = duration > 0 ? duration * (i + 0.5) / FRAME_COUNT : 0;
                grab.seekToSecondPrecise(at);
                BufferedImage frame = AWTUtil.toBufferedImage(grab.getNativeFrame());
                if (frame != null) {
                    frames.add(frame);
                }
                if (duration <= 0) {
                    break;   // длительность неизвестна — довольствуемся первым кадром
                }
            }
        } catch (Exception e) {
            // формат может оказаться неподдерживаемым — это не повод ломать разговор
            log.debug("Видео не разобралось: {}", e.getMessage());
        }
        return frames;
    }

    private static BufferedImage tile(List<BufferedImage> frames) {
        int height = 0;
        List<BufferedImage> scaled = new ArrayList<>();
        for (BufferedImage frame : frames) {
            int scaledHeight = Math.max(1, frame.getHeight() * TILE_WIDTH / Math.max(1, frame.getWidth()));
            BufferedImage resized = new BufferedImage(TILE_WIDTH, scaledHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = resized.createGraphics();
            graphics.drawImage(frame, 0, 0, TILE_WIDTH, scaledHeight, null);
            graphics.dispose();
            scaled.add(resized);
            height += scaledHeight;
        }

        BufferedImage strip = new BufferedImage(TILE_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = strip.createGraphics();
        int y = 0;
        for (BufferedImage frame : scaled) {
            graphics.drawImage(frame, 0, y, null);
            y += frame.getHeight();
        }
        graphics.dispose();
        return strip;
    }

    private static byte[] toJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
