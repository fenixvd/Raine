package ru.rainedev.raine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import it.tdlight.Init;
import org.junit.jupiter.api.Test;

/**
 * Нативы TDLib подтягиваются из jar под конкретную платформу.
 * Если classifier в build.gradle.kts не совпал с системой, падать будет здесь,
 * а не в рантайме на живой сессии.
 */
class NativesSmokeTest {

    @Test
    void nativeLibraryLoads() {
        assertDoesNotThrow(Init::init);
    }
}
