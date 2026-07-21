package ru.rainedev.raine.telegram;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Придерживает поток обращений к Telegram.
 * <p>
 * Telegram строго относится к сторонним клиентам: слишком частые запросы
 * выглядят как автоматизация и приводят к блокировке аккаунта. Пауза после
 * пачки запросов стоит секунды, потерянный аккаунт — всего.
 */
public final class Throttle {

    private static final Logger log = LoggerFactory.getLogger(Throttle.class);

    /** Столько обращений подряд ещё выглядят как живой человек. */
    private static final int BURST = 20;

    private static final Duration PAUSE = Duration.ofSeconds(1);

    private final AtomicInteger sinceLastPause = new AtomicInteger();
    private final AtomicLong lastPauseAt = new AtomicLong(System.nanoTime());

    public void allow() {
        long now = System.nanoTime();
        if (now - lastPauseAt.get() > PAUSE.toNanos()) {
            // прошла секунда — счётчик начинается заново
            lastPauseAt.set(now);
            sinceLastPause.set(0);
            return;
        }
        if (sinceLastPause.incrementAndGet() < BURST) {
            return;
        }
        log.debug("Слишком часто обращаюсь к Telegram — притормаживаю");
        try {
            Thread.sleep(PAUSE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lastPauseAt.set(System.nanoTime());
        sinceLastPause.set(0);
    }
}
