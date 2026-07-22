package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RestStopTest {

    @Test
    void sleepingUntilMorningDoesNotHoldUpTheShutdown() throws InterruptedException {
        // иначе остановка среди ночи ждала бы её пробуждения — то есть до утра
        Rest rest = new Rest(new Random(1));
        rest.night(new NightSleep(
                java.time.LocalTime.of(0, 0), java.time.LocalTime.of(0, 1),
                java.time.LocalTime.of(23, 58), java.time.LocalTime.of(23, 59),
                java.time.Clock.fixed(java.time.Instant.parse("2026-07-22T01:00:00Z"),
                        java.time.ZoneOffset.UTC),
                new Random(1)));

        CountDownLatch asleep = new CountDownLatch(1);
        Thread sleeper = Thread.ofVirtual().start(() -> {
            asleep.countDown();
            rest.maybeRest();
        });
        assertTrue(asleep.await(2, TimeUnit.SECONDS));
        Thread.sleep(300);

        long startedAt = System.currentTimeMillis();
        rest.stop();
        sleeper.join(Duration.ofSeconds(5));

        assertTrue(!sleeper.isAlive(), "должна проснуться на остановку");
        assertTrue(System.currentTimeMillis() - startedAt < 3000, "и почти сразу, а не по будильнику");
    }

    @Test
    void afterStopSheDoesNotLieDownAgain() {
        Rest rest = new Rest(new Random(1));
        rest.stop();

        long startedAt = System.currentTimeMillis();
        rest.maybeRest();

        assertTrue(System.currentTimeMillis() - startedAt < 500, "никаких новых отлучек после остановки");
    }
}
