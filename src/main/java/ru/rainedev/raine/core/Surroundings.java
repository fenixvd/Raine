package ru.rainedev.raine.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Что вокруг: время суток и погода за окном.
 * <p>
 * Без этого она живёт вне мира — не знает, что сейчас ночь или что третий день
 * льёт дождь. А человеку такие вещи задают настроение и дают о чём заговорить.
 */
public final class Surroundings {

    private static final Logger log = LoggerFactory.getLogger(Surroundings.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Погода меняется медленно — спрашивать чаще незачем. */
    private static final Duration WEATHER_FRESHNESS = Duration.ofMinutes(30);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final boolean enabled;
    private final double latitude;
    private final double longitude;

    private String weather = "";
    private Instant weatherAsOf = Instant.EPOCH;

    public Surroundings(boolean enabled, double latitude, double longitude) {
        this.enabled = enabled;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /** Дополнение к системному промпту — короткое, оно читается каждый раз. */
    public String asPromptSuffix() {
        if (!enabled) {
            return "";
        }
        LocalTime now = LocalTime.now();
        StringBuilder out = new StringBuilder("<around_you>\nСейчас ")
                .append(partOfDay(now)).append(", ").append(TIME.format(now)).append('.');

        String outside = currentWeather();
        if (!outside.isEmpty()) {
            out.append(" За окном ").append(outside).append('.');
        }
        return out.append("\n</around_you>\n").toString();
    }

    static String partOfDay(LocalTime time) {
        int hour = time.getHour();
        if (hour < 5) {
            return "глубокая ночь";
        }
        if (hour < 11) {
            return "утро";
        }
        if (hour < 17) {
            return "день";
        }
        if (hour < 23) {
            return "вечер";
        }
        return "ночь";
    }

    private String currentWeather() {
        if (Duration.between(weatherAsOf, Instant.now()).compareTo(WEATHER_FRESHNESS) < 0) {
            return weather;
        }
        weatherAsOf = Instant.now();
        try {
            String url = ("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                    + "&current=temperature_2m,weather_code").formatted(latitude, longitude);
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return weather;
            }
            JsonNode current = mapper.readTree(response.body()).path("current");
            int temperature = (int) Math.round(current.path("temperature_2m").asDouble());
            weather = "%s, %+d".formatted(describe(current.path("weather_code").asInt(-1)), temperature);
            log.debug("Погода обновлена: {}", weather);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // без погоды разговор беднее, но продолжается
            log.debug("Погода недоступна: {}", e.getMessage());
        }
        return weather;
    }

    /** Коды погоды по стандарту, которым отвечает служба прогноза. */
    static String describe(int code) {
        return switch (code) {
            case 0 -> "ясно";
            case 1, 2 -> "переменная облачность";
            case 3 -> "пасмурно";
            case 45, 48 -> "туман";
            case 51, 53, 55, 56, 57 -> "морось";
            case 61, 63, 80, 81 -> "дождь";
            case 65, 82 -> "ливень";
            case 66, 67 -> "ледяной дождь";
            case 71, 73, 75, 77, 85, 86 -> "снег";
            case 95, 96, 99 -> "гроза";
            default -> "непонятная погода";
        };
    }
}
