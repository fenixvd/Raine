package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ThrottleTest {

    @Test
    @Timeout(10)
    void aFewCallsGoThroughWithoutWaiting() {
        Throttle throttle = new Throttle();
        Instant start = Instant.now();

        for (int i = 0; i < 15; i++) {
            throttle.allow();
        }

        assertTrue(Duration.between(start, Instant.now()).toMillis() < 500, "редкие обращения задерживать незачем");
    }

    @Test
    @Timeout(20)
    void aBurstIsSlowedDown() {
        // Telegram блокирует аккаунты за слишком частые обращения сторонних клиентов
        Throttle throttle = new Throttle();
        Instant start = Instant.now();

        for (int i = 0; i < 45; i++) {
            throttle.allow();
        }

        assertTrue(Duration.between(start, Instant.now()).toMillis() >= 900,
                "поток запросов обязан притормаживаться");
    }
}
