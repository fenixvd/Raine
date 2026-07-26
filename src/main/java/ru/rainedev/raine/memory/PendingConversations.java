package ru.rainedev.raine.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.Message;

/**
 * Разговор, который не удалось пересказать в дневник, ждёт на диске.
 * <p>
 * Пересказ делает модель, а модель — за сетью. Оборвалась связь на остановке —
 * и день, прожитый в оперативной памяти, исчезал вместе с процессом. Здесь он
 * ложится сырым и разбирается при следующем запуске; в худшем случае его можно
 * прочитать глазами — файл человекочитаемый.
 */
public final class PendingConversations {

    private static final Logger log = LoggerFactory.getLogger(PendingConversations.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dir;

    public PendingConversations(Path dir) {
        this.dir = dir;
    }

    /** Отложить разговор целиком — со всеми вызовами инструментов, как он был. */
    public void keep(List<Message> conversation) {
        if (conversation.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(System.currentTimeMillis() + ".json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), conversation);
            log.info("Разговор отложен до лучшей связи: {}", file.getFileName());
        } catch (IOException | RuntimeException e) {
            // дальше спасать нечем: этот разговор действительно потерян
            log.error("Разговор не удалось отложить", e);
        }
    }

    /** @return отложенное, самое давнее первым */
    public List<Path> waiting() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        } catch (IOException e) {
            log.warn("Не удалось заглянуть в отложенные разговоры: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Message> read(Path file) {
        try {
            return MAPPER.readValue(file.toFile(), new TypeReference<List<Message>>() {});
        } catch (IOException | RuntimeException e) {
            log.warn("Отложенный разговор {} не читается: {}", file.getFileName(), e.getMessage());
            return List.of();
        }
    }

    /** Разобранное убираем: иначе оно будет пересказываться каждый запуск. */
    public void done(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Отложенный разговор {} не удалился: {}", file.getFileName(), e.getMessage());
        }
    }
}
