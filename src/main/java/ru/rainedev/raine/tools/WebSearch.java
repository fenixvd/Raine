package ru.rainedev.raine.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Поиск в интернете — для того, чего в дневнике быть не может: погода, новости, факты. */
public final class WebSearch {

    private static final Logger log = LoggerFactory.getLogger(WebSearch.class);

    private static final int MAX_RESULTS = 5;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private final String url;
    private final String apiKey;

    public WebSearch(String url, String apiKey) {
        this.url = url;
        this.apiKey = apiKey;
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** @return размеченные результаты или пояснение, почему их нет */
    public String search(String query) {
        if (!isAvailable()) {
            return "No web search available";
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("query", query);
            body.put("max_results", MAX_RESULTS);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Веб-поиск вернул {}: {}", response.statusCode(), response.body());
                // причина передаётся как есть: «кончилась квота» и «нет сети» —
                // это разные вещи, и решать, что делать дальше, ей
                return "Web search failed (%d): %s".formatted(response.statusCode(), shorten(response.body()));
            }
            return render(mapper.readTree(response.body()).path("results"), query);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Web search interrupted";
        } catch (Exception e) {
            log.warn("Веб-поиск не удался: {}", e.getMessage());
            return "Web search failed: " + e.getMessage();
        }
    }

    /** Ответ службы бывает многословным, а в контексте нужна суть. */
    private static String shorten(String body) {
        String text = body == null ? "" : body.strip().replace("\n", " ");
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private static String render(JsonNode results, String query) {
        if (!results.isArray() || results.isEmpty()) {
            return "Nothing found on the web for: " + query;
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode result : results) {
            out.append("<web_search_result title=\"%s\" url=\"%s\">\n%s\n</web_search_result>\n".formatted(
                    result.path("title").asText(""),
                    result.path("url").asText(""),
                    result.path("content").asText("")));
        }
        return out.toString();
    }
}
