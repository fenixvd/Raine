package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class ModelTextTest {

    @Test
    void wrapperAndFenceAreStripped() {
        assertEquals("суть", ModelText.unwrap("""
                ```xml
                <things_to_remember>
                суть
                </things_to_remember>
                ```""", "things_to_remember"));
    }

    @Test
    void tagWithForeignMarkupIsRecognised() {
        // память, перенесённая с прежней машины, приходит с разметкой чужой модели
        String cleaned = ModelText.unwrap(
                "<｜｜DSML｜｜things_to_remember>\nсуть\n</｜｜DSML｜｜things_to_remember>",
                "things_to_remember");

        assertEquals("суть", cleaned);
        assertFalse(cleaned.contains("DSML"));
    }

    @Test
    void plainTextSurvivesUntouched() {
        assertEquals("просто заметка", ModelText.unwrap("просто заметка", "things_to_remember"));
        assertEquals("", ModelText.unwrap(null, "things_to_remember"));
    }
}
