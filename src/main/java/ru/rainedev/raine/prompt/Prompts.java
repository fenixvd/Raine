package ru.rainedev.raine.prompt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.rainedev.raine.config.Config;

/**
 * Промпты лежат обычными файлами снаружи jar, поэтому характер правится
 * без пересборки. В текстах встречаются плейсхолдеры вида ${CHARACTER_NAME}.
 */
public final class Prompts {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Prompts.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z_]+)}");

    /** Заголовок файла между строками "---" — комментарий для людей, модели он не нужен. */
    private static final Pattern HEADER = Pattern.compile("\\A---\\s*\\n.*?\\n---\\s*\\n", Pattern.DOTALL);

    private final Path dir;
    private final Map<String, String> variables;

    public Prompts(Path dir, Config.Character character) {
        this.dir = dir;
        this.variables = Map.of(
                "CHARACTER_NAME", character.name(),
                "CHARACTER_NICKNAME", character.nickname(),
                "PAPIK_NAME", character.papikName());
    }

    /** Системный промпт: правила работы, характер и внешность — именно в этом порядке. */
    public String system(String suffix) {
        StringBuilder result = new StringBuilder()
                .append(load("system.md"))
                .append("\n\n")
                .append(load("character_base.md"))
                .append("\n\n<your_appearance>\n")
                .append(load("character_appearance.md"))
                .append("\n</your_appearance>\n");
        if (suffix != null && !suffix.isBlank()) {
            result.append(suffix);
        }
        return result.toString();
    }

    /**
     * Читает промпт, срезает служебный заголовок и подставляет переменные.
     * <p>
     * Файл читается каждый раз заново: правка характера подхватывается на ходу,
     * без перезапуска. Если файла рядом нет, он выкладывается из сборки — так
     * бот запускается «из коробки», а не падает на первом же промпте.
     */
    public String load(String fileName) {
        Path file = dir.resolve(fileName);
        if (!Files.exists(file)) {
            layOut(fileName, file);
        }
        String raw;
        try {
            raw = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Не найден промпт " + file, e);
        }
        return substitute(HEADER.matcher(raw).replaceFirst("").strip());
    }

    /** Промпт, читаемый в тот момент, когда он понадобится, а не при запуске. */
    public java.util.function.Supplier<String> lazy(String fileName) {
        return () -> load(fileName);
    }

    private void layOut(String fileName, Path file) {
        try (var bundled = Prompts.class.getResourceAsStream("/prompts/" + fileName)) {
            if (bundled == null) {
                throw new IllegalStateException("Нет промпта " + file + ", и в сборке его тоже нет");
            }
            Files.createDirectories(dir);
            Files.write(file, bundled.readAllBytes());
            log.info("Промпт {} выложен из сборки", file);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось выложить промпт " + file, e);
        }
    }

    private String substitute(String text) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variables.get(name);
            if (value == null) {
                throw new IllegalStateException("В промпте встретилась неизвестная переменная ${" + name + "}");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
