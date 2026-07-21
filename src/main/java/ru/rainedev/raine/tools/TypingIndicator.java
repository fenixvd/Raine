package ru.rainedev.raine.tools;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.rainedev.raine.telegram.TelegramActions;

/**
 * Держит «печатает…» всё время, пока инструмент работает.
 * <p>
 * Telegram гасит индикатор через несколько секунд, поэтому его подновляют.
 * Показывать его только на время задержки набора мало: пока считается вектор,
 * грузится снимок или синтезируется голос, собеседник видит пустоту — а по ту
 * сторону это выглядит как «прочитала и молчит».
 */
public final class TypingIndicator implements AutoCloseable {

    /** Индикатор в Telegram живёт около пяти секунд — подновляем чуть чаще. */
    private static final Duration REFRESH = Duration.ofSeconds(4);

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread thread;

    private TypingIndicator(Runnable ping) {
        ping.run();
        this.thread = Thread.ofVirtual().name("raine-typing").start(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(REFRESH);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (running.get()) {
                    ping.run();
                }
            }
        });
    }

    public static TypingIndicator typing(TelegramActions actions, long chatId) {
        return new TypingIndicator(() -> actions.typing(chatId));
    }

    public static TypingIndicator recordingVoice(TelegramActions actions, long chatId) {
        return new TypingIndicator(() -> actions.recordingVoice(chatId));
    }

    public static TypingIndicator uploadingPhoto(TelegramActions actions, long chatId) {
        return new TypingIndicator(() -> actions.uploadingPhoto(chatId));
    }

    @Override
    public void close() {
        running.set(false);
        thread.interrupt();
    }
}
