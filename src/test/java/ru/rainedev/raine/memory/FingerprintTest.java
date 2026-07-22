package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class FingerprintTest {

    @Test
    void sameThoughtWrappedDifferentlyIsTheSame() {
        assertEquals(Fingerprint.of("мы ездили на Байкал"),
                Fingerprint.of("  Мы  ездили\nна Байкал  "));
    }

    @Test
    void differentTextsDiffer() {
        assertNotEquals(Fingerprint.of("мы ездили на Байкал"), Fingerprint.of("мы ездили на Алтай"));
    }

    @Test
    void nothingIsNotAnError() {
        assertEquals(0, Fingerprint.of(null));
    }
}
