package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RestTest {

    /** Позволяет задать, выпадет ли отдых и насколько длинный. */
    private record FixedRandom(double chance, int minutes) implements RandomGenerator {
        @Override
        public double nextDouble() {
            return chance;
        }

        @Override
        public int nextInt(int bound) {
            return Math.min(minutes, bound - 1);
        }

        @Override
        public long nextLong() {
            return 0;
        }
    }

    @Test
    @Timeout(5)
    void usuallyDoesNotRest() {
        Rest rest = new Rest(new FixedRandom(0.9, 0));

        rest.maybeRest();

        assertFalse(rest.isResting());
    }

    @Test
    @Timeout(10)
    void ownerMessageBringsHerBack() throws Exception {
        // отдых длинный, но сообщение владельца должно поднять почти сразу
        Rest rest = new Rest(new FixedRandom(0.0, 100));

        Thread resting = Thread.ofVirtual().start(rest::maybeRest);
        Instant start = Instant.now();
        while (!rest.isResting() && Duration.between(start, Instant.now()).toSeconds() < 3) {
            Thread.sleep(10);
        }
        assertTrue(rest.isResting(), "должна была отойти");

        rest.wakeUp();
        resting.join(Duration.ofSeconds(5));

        assertFalse(resting.isAlive(), "сообщение владельца обязано будить, а не ждать конца отдыха");
        assertFalse(rest.isResting());
    }

    @Test
    @Timeout(5)
    void wakeUpBeforeRestingIsNotLost() throws Exception {
        Rest rest = new Rest(new FixedRandom(0.0, 0));
        rest.wakeUp();

        Thread resting = Thread.ofVirtual().start(rest::maybeRest);
        resting.join(Duration.ofSeconds(3));

        assertFalse(resting.isAlive(), "если её только что позвали, отдых не начинается");
    }
}
