package ru.rainedev.raine.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Генерация картинок на общей сети добровольцев: работа ставится в очередь,
 * а результат забирается, когда чей-то компьютер её выполнит.
 */
public final class HordeClient {

    private static final Logger log = LoggerFactory.getLogger(HordeClient.class);

    /** Размеры должны быть кратны 64 — это требование самих моделей. */
    private static final int DIMENSION_STEP = 64;
    private static final int MIN_DIMENSION = 512;

    /**
     * Больше запрашивать нельзя: за крупные картинки очередь требует запас
     * очков вперёд, и заявка отклоняется тем чаще, чем меньше баланс.
     */
    private static final int MAX_DIMENSION = 1024;

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(4);
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private final String baseUrl;
    private final String apiKey;
    private final List<String> models;

    public HordeClient(String baseUrl, String apiKey, List<String> models) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.apiKey = apiKey;
        this.models = models;
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** @return содержимое готовой картинки */
    public byte[] generate(String positive, String negative, int width, int height, double cfgScale, int steps) {
        String id = submit(positive, negative, width, height, cfgScale, steps);
        waitUntilDone(id);
        return download(imageUrl(id));
    }

    private String submit(String positive, String negative, int width, int height, double cfgScale, int steps) {
        ObjectNode params = mapper.createObjectNode();
        params.put("sampler_name", "k_euler_a");
        // очередь отклоняет дробный масштаб с длинным хвостом
        params.put("cfg_scale", Math.round(Math.clamp(cfgScale, 4.0, 8.0) * 100.0) / 100.0);
        params.put("width", sanitize(width));
        params.put("height", sanitize(height));
        params.put("steps", Math.clamp(steps, 10, 30));
        params.put("n", 1);
        params.put("karras", true);
        params.put("clip_skip", 2);

        ObjectNode body = mapper.createObjectNode();
        // отрицательная часть отделяется от положительной двумя решётками
        body.put("prompt", negative == null || negative.isBlank() ? positive : positive + " ### " + negative);
        body.set("params", params);
        body.put("nsfw", true);
        body.put("censor_nsfw", false);
        body.put("shared", true);   // отдавать результаты обратно в сеть — так растёт приоритет
        body.put("trusted_workers", false);
        body.put("r2", true);
        body.set("models", mapper.valueToTree(models));

        JsonNode response = request("generate/async", body);
        if (!response.hasNonNull("id")) {
            throw new IllegalStateException("Очередь не приняла заявку: " + response);
        }
        return response.get("id").asText();
    }

    private void waitUntilDone(String id) {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            sleep();
            JsonNode check = get("generate/check/" + id);
            if (check.path("faulted").asBoolean(false)) {
                throw new IllegalStateException("Очередь не смогла выполнить работу");
            }
            if (check.path("done").asBoolean(false)) {
                return;
            }
            log.debug("Ожидание очереди: ждём {}, работают {}",
                    check.path("waiting").asInt(), check.path("processing").asInt());
        }
        throw new IllegalStateException("Очередь не успела за отведённое время");
    }

    private String imageUrl(String id) {
        JsonNode generations = get("generate/status/" + id).path("generations");
        if (!generations.isArray() || generations.isEmpty()) {
            throw new IllegalStateException("Работа выполнена, но картинки нет");
        }
        return generations.get(0).path("img").asText();
    }

    private byte[] download(String url) {
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2 || response.body().length == 0) {
                throw new IllegalStateException("Картинку не удалось забрать (%d)".formatted(response.statusCode()));
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Загрузка прервана", e);
        } catch (IOException e) {
            throw new IllegalStateException("Картинку не удалось забрать: " + e.getMessage(), e);
        }
    }

    private JsonNode request(String path, ObjectNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .header("apikey", apiKey)
                    .timeout(Duration.ofMinutes(1))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Очередь отказала (%d): %s"
                        .formatted(response.statusCode(), response.body()));
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Запрос прерван", e);
        } catch (IOException e) {
            throw new IllegalStateException("Очередь недоступна: " + e.getMessage(), e);
        }
    }

    private JsonNode get(String path) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .header("apikey", apiKey)
                            .timeout(Duration.ofSeconds(30))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание прервано", e);
        } catch (IOException e) {
            throw new IllegalStateException("Очередь недоступна: " + e.getMessage(), e);
        }
    }

    static int sanitize(int value) {
        int clamped = Math.clamp(value, MIN_DIMENSION, MAX_DIMENSION);
        return Math.max(MIN_DIMENSION, clamped / DIMENSION_STEP * DIMENSION_STEP);
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание прервано", e);
        }
    }
}
