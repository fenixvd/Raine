package ru.rainedev.raine.telegram;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Ожидание ответа Telegram — всегда с оглядкой на часы.
 * <p>
 * Ответ может не прийти вовсе: связь моргнула, запрос потерялся, сервер
 * промолчал. Бесконечное ожидание в таком случае останавливает не один вызов,
 * а всю её жизнь целиком — она замирает посреди хода и не отвечает никому,
 * причём в журнале об этом ни строчки. Лучше сдаться через полминуты
 * и сказать об этом вслух.
 */
final class Await {

    /** Дольше ждать нечего: живой человек за это время успел бы переспросить. */
    private static final Duration LIMIT = Duration.ofSeconds(30);

    private Await() {}

    static <T> T reply(CompletableFuture<T> request, String what) {
        try {
            return request.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            request.cancel(true);
            throw new IllegalStateException(
                    "Telegram не ответил на " + what + " за " + LIMIT.toSeconds() + " с");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание ответа Telegram прервано");
        } catch (ExecutionException e) {
            Throwable reason = e.getCause() == null ? e : e.getCause();
            if (reason instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(reason);
        }
    }
}
