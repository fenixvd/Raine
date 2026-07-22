package ru.rainedev.raine.vision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Достаёт звуковую дорожку из видео.
 * <p>
 * Чистого Java-разбора контейнеров у нас нет, поэтому дорожку вынимает ffmpeg,
 * если он есть в системе. Зависимость необязательная: нет ffmpeg — видео просто
 * останется немым, как было. Тащить его в обязательные ради одного случая
 * не хочется, а когда он есть, половина смысла ролика перестаёт теряться.
 */
public final class VideoAudio {

    private static final Logger log = LoggerFactory.getLogger(VideoAudio.class);

    /** Дольше ждать нечего: ролик из переписки — это секунды, а не фильм. */
    private static final int TIMEOUT_SECONDS = 60;

    /** Больше и не нужно: речь распознаётся и с такой дорожки. */
    private static final String BITRATE = "24k";

    private static volatile Boolean available;

    private VideoAudio() {}

    /** @return есть ли чем вынимать дорожку */
    public static boolean isAvailable() {
        Boolean known = available;
        if (known == null) {
            known = run(List.of("ffmpeg", "-version"));
            available = known;
            log.info(known ? "ffmpeg найден: звук из видео будет слышен" : "ffmpeg не найден: видео останется немым");
        }
        return known;
    }

    /**
     * @return файл с дорожкой или пусто, если звука нет или вынуть его нечем.
     *         Файл временный — удалить его должен вызывающий
     */
    public static Optional<Path> extract(Path video) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        Path audio;
        try {
            audio = Files.createTempFile("raine-audio-", ".ogg");
        } catch (IOException e) {
            log.debug("Не нашлось места под дорожку: {}", e.getMessage());
            return Optional.empty();
        }
        boolean extracted = run(List.of("ffmpeg", "-y", "-loglevel", "error",
                "-i", video.toAbsolutePath().toString(),
                "-vn", "-ac", "1", "-c:a", "libopus", "-b:a", BITRATE,
                audio.toAbsolutePath().toString()));
        try {
            if (extracted && Files.size(audio) > 0) {
                return Optional.of(audio);
            }
            // немое видео — обычное дело, и это не ошибка
            Files.deleteIfExists(audio);
        } catch (IOException e) {
            log.debug("Дорожка не пригодилась: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Кадры через ffmpeg — запасной путь, когда чистый разбор не справился
     * с форматом (а он спотыкается, например, на чересстрочном h264).
     *
     * @param count сколько кадров нужно
     * @return файлы кадров по порядку; удалить их должен вызывающий
     */
    public static List<Path> frames(Path video, int count, double duration) {
        if (!isAvailable() || count <= 0) {
            return List.of();
        }
        List<Path> frames = new java.util.ArrayList<>();
        try {
            Path dir = Files.createTempDirectory("raine-frames-");
            for (int i = 0; i < count; i++) {
                double at = count > 1 && duration > 0 ? duration * i / (count - 1) : 0;
                Path frame = dir.resolve("frame-" + i + ".jpg");
                boolean grabbed = run(List.of("ffmpeg", "-y", "-loglevel", "error",
                        "-ss", String.valueOf(at), "-i", video.toAbsolutePath().toString(),
                        "-frames:v", "1", "-vf", "scale='min(512,iw)':-2",
                        frame.toAbsolutePath().toString()));
                if (grabbed && Files.exists(frame) && Files.size(frame) > 0) {
                    frames.add(frame);
                }
            }
        } catch (IOException e) {
            log.debug("Кадры через ffmpeg не вышли: {}", e.getMessage());
        }
        return frames;
    }

    /** Длительность видео в секундах, 0 — если неизвестна. */
    public static double duration(Path video) {
        if (!isAvailable()) {
            return 0;
        }
        try {
            Process process = new ProcessBuilder(List.of("ffprobe", "-v", "error",
                    "-show_entries", "format=duration", "-of", "default=nw=1:nk=1",
                    video.toAbsolutePath().toString()))
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).strip();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return 0;
            }
            return Double.parseDouble(output);
        } catch (IOException | NumberFormatException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private static boolean run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.debug("ffmpeg не уложился в отведённое время");
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
