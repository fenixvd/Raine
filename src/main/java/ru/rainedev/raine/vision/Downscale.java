package ru.rainedev.raine.vision;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Уменьшает картинку перед отправкой модели.
 * <p>
 * Смотрит она всё равно на уменьшенное, а платится за каждый байт: снимок
 * с телефона на четыре тысячи точек по стороне в base64 — это мегабайты
 * впустую и заметно более медленный ответ.
 */
public final class Downscale {

    private static final Logger log = LoggerFactory.getLogger(Downscale.class);

    /** Сторона, до которой ужимается всё, что уходит смотреть. */
    public static final int MAX_SIDE = 672;

    private Downscale() {}

    /** @return уменьшенный JPEG или исходные байты, если картинка не разобралась */
    public static byte[] toFit(byte[] image, int maxSide) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
            if (source == null) {
                return image;
            }
            int width = source.getWidth();
            int height = source.getHeight();
            if (width <= maxSide && height <= maxSide) {
                return image;
            }
            double scale = Math.min(maxSide / (double) width, maxSide / (double) height);
            int newWidth = Math.max(1, (int) (width * scale));
            int newHeight = Math.max(1, (int) (height * scale));

            BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = resized.createGraphics();
            graphics.drawImage(source, 0, 0, newWidth, newHeight, null);
            graphics.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(resized, "jpg", out);
            log.debug("Картинка ужата с {}×{} до {}×{}", width, height, newWidth, newHeight);
            return out.toByteArray();
        } catch (Exception e) {
            // формат может оказаться незнакомым — тогда отправляем как есть
            log.debug("Картинку не уменьшить: {}", e.getMessage());
            return image;
        }
    }

    public static byte[] toFit(byte[] image) {
        return toFit(image, MAX_SIDE);
    }
}
