package ru.rainedev.raine.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Чтение настроек с оглядкой на человека, который потом будет их править.
 * <p>
 * Три вещи, которых не даёт обычный {@link Properties}:
 * <ul>
 *   <li>каждая настройка объявляется вместе с пояснением, и недостающие
 *       дописываются в файл — после обновления видно, что появилось нового,
 *       а не приходится искать это в исходниках;</li>
 *   <li>незнакомый ключ не проходит молча: опечатка в имени иначе выглядит
 *       как «настройка не работает» без единого следа;</li>
 *   <li>обязательное значение требуется с внятным объяснением, где его взять.</li>
 * </ul>
 */
public final class Settings {

    private static final Logger log = LoggerFactory.getLogger(Settings.class);

    /** Что за настройка, чтобы дописать её в файл с пояснением. */
    private record Known(String comment, String fallback) {}

    private final Properties props;
    private final Path file;
    private final Map<String, Known> known = new LinkedHashMap<>();

    public Settings(Properties props, Path file) {
        this.props = props;
        this.file = file;
    }

    public static Settings read(Path file) {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (var in = Files.newBufferedReader(file)) {
                props.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException("Не удалось прочитать " + file, e);
            }
        }
        return new Settings(props, file);
    }

    /**
     * @param comment пояснение, которое попадёт в файл рядом с настройкой
     */
    public String get(String key, String fallback, String comment) {
        known.put(key, new Known(comment, fallback));
        String env = System.getenv("RAINE_" + key.toUpperCase());
        if (env != null && !env.isBlank()) {
            return env;
        }
        return props.getProperty(key, fallback);
    }

    public int integer(String key, int fallback, String comment) {
        return Integer.parseInt(number(key, String.valueOf(fallback), comment));
    }

    public long number(String key, long fallback, String comment) {
        return Long.parseLong(number(key, String.valueOf(fallback), comment));
    }

    public double fraction(String key, double fallback, String comment) {
        return Double.parseDouble(number(key, String.valueOf(fallback), comment));
    }

    public boolean flag(String key, boolean fallback, String comment) {
        return Boolean.parseBoolean(get(key, String.valueOf(fallback), comment));
    }

    public Path path(String key, String fallback, String comment) {
        return Path.of(get(key, fallback, comment));
    }

    public java.time.LocalTime time(String key, String fallback, String comment) {
        return java.time.LocalTime.parse(get(key, fallback, comment));
    }

    /** Значение, без которого запускаться бессмысленно. */
    public String require(String key, String comment) {
        String value = get(key, null, comment);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Не задан параметр '" + key + "' (" + comment + "). Добавь его в " + file
                            + " или задай переменную RAINE_" + key.toUpperCase());
        }
        return value;
    }

    /** Число, у которого пустая строка означает «как раньше». */
    private String number(String key, String fallback, String comment) {
        String value = get(key, fallback, comment);
        try {
            Double.parseDouble(value);
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Параметр '" + key + "' должен быть числом, а задано \"" + value + "\"");
        }
    }

    /**
     * Дописывает в файл настройки, которых там ещё нет, и жалуется на те,
     * которых не знает. Вызывается после того, как все настройки прочитаны.
     */
    public void sync() {
        for (String key : props.stringPropertyNames()) {
            if (!known.containsKey(key)) {
                log.warn("Неизвестная настройка '{}' в {} — опечатка? Она ни на что не влияет.", key, file);
            }
        }

        List<String> missing = new ArrayList<>();
        for (var entry : known.entrySet()) {
            if (props.containsKey(entry.getKey()) || entry.getValue().fallback() == null) {
                continue;
            }
            missing.add("");
            missing.add("# " + entry.getValue().comment());
            missing.add(entry.getKey() + "=" + entry.getValue().fallback());
        }
        if (missing.isEmpty() || !Files.exists(file)) {
            return;
        }
        try {
            List<String> block = new ArrayList<>();
            block.add("");
            block.add("# --- добавлено при запуске " + java.time.LocalDate.now() + " ---");
            block.addAll(missing);
            Files.write(file, String.join(System.lineSeparator(), block).concat(System.lineSeparator())
                            .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
            log.info("В {} дописано новых настроек: {}", file, missing.size() / 3);
        } catch (IOException e) {
            // не повод не запускаться: настройки уже прочитаны
            log.warn("Не удалось дописать настройки в {}: {}", file, e.getMessage());
        }
    }
}
