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

    /**
     * По этому заголовку сеть добровольцев узнаёт клиента. Без него запросы
     * выглядят безымянными и попадают под ограничения первыми.
     */
    private static final String CLIENT_AGENT = "raine:1.0:github.com/fenixvd";

    /** Сколько раз повторить запрос, прежде чем считать работу потерянной. */
    private static final int ATTEMPTS = 3;

    /**
     * Ждать зависшую раздачу три минуты незачем: проще оборвать и повторить —
     * картинка на раздаче живёт недолго.
     */
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(45);

    /** Насколько сильно переписывать картинку при дорисовке. */
    private static final double HIRES_DENOISING = 0.7;
    private static final int MIN_DIMENSION = 512;

    /**
     * Больше запрашивать нельзя: за крупные картинки очередь требует запас
     * очков вперёд, и заявка отклоняется тем чаще, чем меньше баланс.
     */
    private static final int MAX_DIMENSION = 1024;

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(4);

    /**
     * Сколько ждать своей очереди. Сеть добровольцев — не своя видеокарта:
     * в загруженный час до картинки бывает три четверти часа, и десять минут
     * отсекали почти всё, ни разу не дойдя до рисования.
     */
    private final Duration timeout;

    /** Как часто говорить в журнал, где мы в очереди. */
    private static final int REPORT_EVERY_POLLS = 15;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private final String baseUrl;
    private final String apiKey;
    private final List<String> models;

    public HordeClient(String baseUrl, String apiKey, List<String> models) {
        this(baseUrl, apiKey, models, Duration.ofMinutes(60));
    }

    public HordeClient(String baseUrl, String apiKey, List<String> models, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.apiKey = apiKey;
        this.models = models;
        this.timeout = timeout;
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
        // дорисовка в большем разрешении: картинка рождается в привычном для
        // модели размере и потом дотягивается. Лица и руки от этого заметно
        // ровнее — а именно на них спотыкается проверка качества
        params.put("hires_fix", true);
        params.put("denoising_strength", HIRES_DENOISING);

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
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int polls = 0;
        while (System.currentTimeMillis() < deadline) {
            sleep();
            JsonNode check;
            try {
                check = get("generate/check/" + id);
            } catch (RuntimeException e) {
                // один сорвавшийся опрос — не повод бросать работу: картинка
                // уже оплачена кудосами и, возможно, вот-вот будет готова
                log.warn("Опрос очереди не удался, пробую ещё: {}", e.getMessage());
                continue;
            }
            if (check.path("faulted").asBoolean(false)) {
                throw new IllegalStateException("Очередь не смогла выполнить работу");
            }
            if (check.path("done").asBoolean(false)) {
                return;
            }
            if (!check.path("is_possible").asBoolean(true)) {
                // некому рисовать: ни один доброволец не берёт такую модель
                throw new IllegalStateException("Очередь не может выполнить работу: нет подходящих исполнителей");
            }
            // без этого «долго рисует» неотличимо от «зависла»
            if (++polls % REPORT_EVERY_POLLS == 1) {
                log.info("Стою в очереди: место {}, ждать примерно {} c",
                        check.path("queue_position").asInt(), check.path("wait_time").asInt());
            }
        }
        throw new IllegalStateException(
                "Очередь не успела за отведённое время (" + timeout.toMinutes() + " мин)");
    }

    private String imageUrl(String id) {
        JsonNode generations = retrying(() -> get("generate/status/" + id)).path("generations");
        if (!generations.isArray() || generations.isEmpty()) {
            throw new IllegalStateException("Работа выполнена, но картинки нет");
        }
        return generations.get(0).path("img").asText();
    }

    private byte[] download(String url) {
        return retrying(() -> downloadOnce(url));
    }

    /**
     * Повтор с паузой. Готовая картинка стоила кудосов и живёт на раздаче
     * недолго — терять её из-за одной оборвавшейся передачи обидно.
     */
    private <T> T retrying(java.util.function.Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                last = e;
                log.warn("Попытка {} из {} не удалась: {}", attempt, ATTEMPTS, e.getMessage());
                if (attempt < ATTEMPTS) {
                    sleep(Duration.ofSeconds(2));
                }
            }
        }
        throw last;
    }

    private byte[] downloadOnce(String url) {
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("Client-Agent", CLIENT_AGENT)
                            .timeout(DOWNLOAD_TIMEOUT).GET().build(),
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
                    .header("Client-Agent", CLIENT_AGENT)
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
                            .header("Client-Agent", CLIENT_AGENT)
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
        sleep(POLL_INTERVAL);
    }

    private static void sleep(Duration howLong) {
        try {
            Thread.sleep(howLong);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание прервано", e);
        }
    }
}
