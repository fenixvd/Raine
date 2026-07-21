package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FatalTest {

    @AfterEach
    void forgetHandler() {
        Fatal.handler(null);
    }

    @Test
    void frozenAccountStopsEverything() {
        boolean[] stopped = new boolean[1];
        Fatal.handler(error -> stopped[0] = true);

        assertTrue(Fatal.check(new IllegalStateException("FROZEN_METHOD_INVALID")));
        assertTrue(stopped[0], "после заморозки продолжать нельзя");
    }

    @Test
    void reasonIsFoundDeepInTheChain() {
        // TDLib заворачивает ошибку в несколько слоёв
        Throwable wrapped = new RuntimeException("не удалось отправить",
                new IllegalStateException("Telegram error: USER_DEACTIVATED"));

        assertTrue(Fatal.check(wrapped));
    }

    @Test
    void ordinaryErrorsGoOnAsUsual() {
        boolean[] stopped = new boolean[1];
        Fatal.handler(error -> stopped[0] = true);

        assertFalse(Fatal.check(new IllegalStateException("MESSAGE_ID_INVALID")));
        assertFalse(Fatal.check(new RuntimeException()));
        assertFalse(stopped[0], "обычная ошибка не повод останавливаться");
    }
}
