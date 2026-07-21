package ru.rainedev.raine.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Разбор чисел из ответа модели.
 * <p>
 * Идентификаторы сообщений и чатов модель выдаёт как придётся: числом, строкой,
 * а иногда — в научной записи вроде {@code 7.576e+08}. Обычное чтение целого
 * в таких случаях молча даёт ноль, и реакция, пересылка, правка или удаление
 * промахиваются мимо сообщения, ничего не сообщая. Поэтому берём то, что дали,
 * и приводим к числу сами.
 */
public final class Numbers {

    private static final Logger log = LoggerFactory.getLogger(Numbers.class);

    private Numbers() {}

    /** @return число из поля, если его вообще удалось прочитать */
    public static Optional<Long> longFrom(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        if (node.canConvertToLong()) {
            return Optional.of(node.longValue());
        }
        if (node.isNumber()) {
            long coerced = (long) node.doubleValue();
            log.warn("Число {} приведено к целому: {}", node, coerced);
            return Optional.of(coerced);
        }
        String text = node.asText("").strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(text));
        } catch (NumberFormatException ignored) {
            // может оказаться дробным или в научной записи
        }
        try {
            long coerced = (long) Double.parseDouble(text);
            log.warn("Строка \"{}\" приведена к целому: {}", text, coerced);
            return Optional.of(coerced);
        } catch (NumberFormatException e) {
            log.warn("Не удалось прочитать число из {}", node);
            return Optional.empty();
        }
    }

    /**
     * Признак «да/нет» из поля. Модель присылает то настоящий булев,
     * то строку «true» — понимаем оба.
     */
    public static boolean flagAt(JsonNode arguments, String field) {
        JsonNode node = arguments == null ? null : arguments.get(field);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        return node.isBoolean() ? node.booleanValue() : "true".equalsIgnoreCase(node.asText("").strip());
    }

    /** То же, но из поля объекта. */
    public static Optional<Long> longAt(JsonNode arguments, String field) {
        return longFrom(arguments == null ? null : arguments.get(field));
    }

    /** @param fallback чем заменить, если прочитать не вышло */
    public static long longAt(JsonNode arguments, String field, long fallback) {
        return longAt(arguments, field).orElse(fallback);
    }
}
