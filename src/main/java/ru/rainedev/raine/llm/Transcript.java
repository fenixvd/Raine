package ru.rainedev.raine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Полная запись разговора с моделью: что ушло и что вернулось.
 * <p>
 * В обычный журнал попадают только мысли и действия, а когда она отвечает
 * странно, разбираться приходится именно в запросе целиком: какой был
 * системный промпт, что подмешалось из памяти, какие инструменты были
 * доступны. Держать это в голове невозможно, а стоит запись копейки.
 */
public final class Transcript {

    private static final Logger log = LoggerFactory.getLogger(Transcript.class);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private static volatile Path directory = Path.of("logs", "llm");
    private static volatile boolean enabled = true;

    private Transcript() {}

    public static void directory(Path where) {
        directory = where;
    }

    public static void enabled(boolean value) {
        enabled = value;
    }

    /** Пишет пару «запрос — ответ» двумя файлами с общей отметкой времени. */
    public static void save(JsonNode request, String response) {
        if (!enabled) {
            return;
        }
        String stamp = LocalDateTime.now().format(STAMP);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(stamp + ".0request.json"), request.toPrettyString());
            Files.writeString(directory.resolve(stamp + ".1response.json"), response);
        } catch (IOException e) {
            // запись разговора — удобство, а не условие работы
            log.debug("Не удалось записать разговор с моделью: {}", e.getMessage());
        }
    }
}
