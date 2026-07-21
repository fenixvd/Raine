package ru.rainedev.raine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;

/**
 * Клиент к любому OpenAI-совместимому эндпоинту (routerai, openrouter, ollama).
 * <p>
 * Стриминг сознательно не используется: в агентном цикле важен цельный ответ
 * с вызовами инструментов, а не постепенный вывод текста.
 */
public final class OpenAiCompatibleClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    private static final int MAX_ATTEMPTS = 3;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final Config.Llm config;
    private final String embeddingModel;

    public OpenAiCompatibleClient(Config.Llm config, String embeddingModel) {
        this.config = config;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));
        messages.addAll(history);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model());
        body.put("temperature", config.temperature());
        body.set("messages", mapper.valueToTree(messages));
        if (tools != null && tools.isArray() && !tools.isEmpty()) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }

        String response = post("chat/completions", body, Duration.ofMinutes(5));
        try {
            return mapper.readValue(response, ChatResponse.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать ответ модели", e);
        }
    }

    @Override
    public String describeImage(String model, String systemPrompt, String context, byte[] image) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);

        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);

        // картинка идёт отдельной частью сообщения, а не тегом внутри текста:
        // так её видит любой OpenAI-совместимый шлюз
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        var parts = userMessage.putArray("content");
        parts.addObject().put("type", "text").put("text", context);
        parts.addObject()
                .put("type", "image_url")
                .putObject("image_url")
                .put("url", "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(image));

        String response = post("chat/completions", body, Duration.ofMinutes(3));
        try {
            return mapper.readValue(response, ChatResponse.class).text();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать описание картинки", e);
        }
    }

    @Override
    public double[] embedding(String input) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", embeddingModel);
        // пустую строку эндпоинт не принимает, а пустой текст встречается
        body.put("input", input == null || input.isBlank() ? " " : input);

        String response = post("embeddings", body, Duration.ofMinutes(2));
        try {
            JsonNode vector = mapper.readTree(response).path("data").path(0).path("embedding");
            if (!vector.isArray()) {
                throw new IllegalStateException("Эндпоинт не вернул вектор: " + response);
            }
            double[] result = new double[vector.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = vector.get(i).asDouble();
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать эмбеддинг", e);
        }
    }

    private String post(String path, ObjectNode body, Duration timeout) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось собрать запрос", e);
        }

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    return response.body();
                }
                // 4xx повторять бессмысленно — запрос не станет корректнее сам по себе
                if (response.statusCode() / 100 == 4) {
                    throw new IllegalStateException(
                            "Модель отвергла запрос (%d): %s".formatted(response.statusCode(), response.body()));
                }
                lastFailure = new IllegalStateException(
                        "Ошибка эндпоинта (%d): %s".formatted(response.statusCode(), response.body()));
            } catch (IOException e) {
                lastFailure = new UncheckedIOException("Сеть недоступна", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Запрос прерван", e);
            }

            if (attempt < MAX_ATTEMPTS) {
                log.warn("Попытка {} из {} не удалась: {}", attempt, MAX_ATTEMPTS, lastFailure.getMessage());
                sleep(Duration.ofSeconds(2L * attempt));
            }
        }
        throw lastFailure;
    }

    private String baseUrl() {
        String url = config.baseUrl();
        return url.endsWith("/") ? url : url + "/";
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание прервано", e);
        }
    }
}
