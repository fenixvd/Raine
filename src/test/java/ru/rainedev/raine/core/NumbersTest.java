package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class NumbersTest {

    private static ObjectNode arguments(String json) {
        try {
            return (ObjectNode) new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void plainNumberIsRead() {
        assertEquals(757600000L, Numbers.longAt(arguments("{\"id\":757600000}"), "id", 0));
    }

    @Test
    void scientificNotationIsRead() {
        // некоторые модели выдают идентификаторы именно так
        assertEquals(757600000L, Numbers.longAt(arguments("{\"id\":7.576e+08}"), "id", 0));
    }

    @Test
    void stringIsRead() {
        assertEquals(757600000L, Numbers.longAt(arguments("{\"id\":\"757600000\"}"), "id", 0));
        assertEquals(757600000L, Numbers.longAt(arguments("{\"id\":\"7.576e+08\"}"), "id", 0));
    }

    @Test
    void missingAndGarbageAreEmpty() {
        assertTrue(Numbers.longAt(arguments("{}"), "id").isEmpty());
        assertTrue(Numbers.longAt(arguments("{\"id\":null}"), "id").isEmpty());
        assertTrue(Numbers.longAt(arguments("{\"id\":\"\"}"), "id").isEmpty());
        assertTrue(Numbers.longAt(arguments("{\"id\":\"какой-то текст\"}"), "id").isEmpty());
    }

    @Test
    void negativeChatIdSurvives() {
        // идентификаторы групп отрицательные и очень длинные
        assertEquals(-1001234567890L, Numbers.longAt(arguments("{\"id\":-1001234567890}"), "id", 0));
    }
}
