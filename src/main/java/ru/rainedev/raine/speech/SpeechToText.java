package ru.rainedev.raine.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;

/**
 * Распознавание речи из файла.
 * <p>
 * Голосовые и кружки расшифровывает сам Telegram — за премиум, качественно
 * и бесплатно для нас. Но у обычного видео он этого не делает, а половина
 * смысла ролика обычно как раз в звуке. Для таких случаев есть этот путь.
 */
public final class SpeechToText {

    private static final Logger log = LoggerFactory.getLogger(SpeechToText.class);

    private static final String BOUNDARY = "----RaineAudioBoundary";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private final Config.Hearing config;

    public SpeechToText(Config.Hearing config) {
        this.config = config;
    }

    public boolean isAvailable() {
        return config.enabled() && config.apiKey() != null && !config.apiKey().isBlank();
    }

    /** @return что услышано или пусто, если не вышло */
    public Optional<String> listen(Path audio) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try {
            byte[] body = multipart(audio);
            String url = config.baseUrl().endsWith("/") ? config.baseUrl() : config.baseUrl() + "/";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "audio/transcriptions"))
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                    .header("Authorization", "Bearer " + config.apiKey())
                    .timeout(Duration.ofMinutes(3))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Распознавание речи вернуло {}: {}", response.statusCode(), response.body());
                return Optional.empty();
            }
            String heard = mapper.readTree(response.body()).path("text").asText("").strip();
            return heard.isEmpty() ? Optional.empty() : Optional.of(heard);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            log.warn("Распознать речь не вышло: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Файл отправляется формой — так этот метод и устроен у всех, кто его умеет. */
    private byte[] multipart(Path audio) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(field("model", config.model()));
        out.write(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + audio.getFileName() + "\"\r\n"
                + "Content-Type: audio/ogg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(Files.readAllBytes(audio));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        if (!config.language().isBlank()) {
            out.write(field("language", config.language()));
        }
        out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static byte[] field(String name, String value) {
        return ("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
