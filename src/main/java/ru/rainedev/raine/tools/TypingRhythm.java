package ru.rainedev.raine.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

/**
 * Пауза между сообщениями, соразмерная длине текста.
 * <p>
 * Без неё четыре реплики приходят в одну секунду — сразу видно, что печатал
 * не человек. Если предыдущее сообщение было давно, ждать не нужно: пауза
 * изображает набор текста, а не задумчивость.
 */
public final class TypingRhythm {

    /**
     * Слов в минуту — привычная мера скорости печати. Слово считается за пять
     * знаков, то есть выходит около десяти знаков в секунду: бойко, но
     * по-человечески.
     */
    private static final int MIN_WORDS_PER_MINUTE = 120;
    private static final int MAX_WORDS_PER_MINUTE = 150;

    private static final int CHARS_PER_WORD = 5;

    /** Если с прошлого сообщения прошло больше — это новая мысль, а не продолжение. */
    private static final Duration CONVERSATION_GAP = Duration.ofSeconds(5);

    private static final Duration MAX_DELAY = Duration.ofSeconds(12);

    private final RandomGenerator random;
    private Instant previousMessageAt = Instant.EPOCH;

    public TypingRhythm(RandomGenerator random) {
        this.random = random;
    }

    /** @return сколько ждать перед отправкой сообщения такой длины */
    public Duration delayFor(int messageLength, Instant now) {
        Duration sincePrevious = Duration.between(previousMessageAt, now);
        previousMessageAt = now;
        if (sincePrevious.compareTo(CONVERSATION_GAP) > 0) {
            return Duration.ZERO;
        }
        int wordsPerMinute = MIN_WORDS_PER_MINUTE
                + random.nextInt(MAX_WORDS_PER_MINUTE - MIN_WORDS_PER_MINUTE + 1);
        double charsPerMinute = (double) wordsPerMinute * CHARS_PER_WORD;
        Duration delay = Duration.ofMillis(Math.round((messageLength + 1) * 60_000.0 / charsPerMinute));
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    /**
     * @param keepTyping вызывается по ходу паузы: индикатор набора живёт около
     *                   пяти секунд, и без продления собеседник видит, что она
     *                   «передумала печатать»
     */
    public void sleepBefore(int messageLength, Runnable keepTyping) {
        Duration delay = delayFor(messageLength, Instant.now());
        if (delay.isZero()) {
            return;
        }
        try {
            long left = delay.toMillis();
            while (left > 0) {
                long step = Math.min(left, 4000);
                Thread.sleep(step);
                left -= step;
                if (left > 0) {
                    keepTyping.run();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
