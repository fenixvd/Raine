package ru.rainedev.raine.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Следит за файлом настроек и подхватывает правку на ходу — так же, как
 * промпты.
 * <p>
 * Меняется не всё: номер телефона и папку сессии на живую не подменить, да и
 * незачем. А вот повадки — как часто отлучаться, кому можно писать, насколько
 * строго ловить повторы — правятся именно тогда, когда что-то в её поведении
 * не понравилось, и перезапускать ради этого бота обидно.
 */
public final class LiveConfig implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveConfig.class);

    /** Реже проверять незачем, чаще — тревожить диск впустую. */
    private static final long CHECK_SECONDS = 30;

    private final Path file;
    private final List<Consumer<Config>> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService watcher =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("raine-config").factory());

    private volatile Config current;
    private volatile long seenAt;

    public LiveConfig(Path file, Config initial) {
        this.file = file;
        this.current = initial;
        this.seenAt = changedAt();
    }

    public Config current() {
        return current;
    }

    /** Что переприменить, когда файл поправили. Вызывается сразу и потом при каждой правке. */
    public LiveConfig onChange(Consumer<Config> listener) {
        listeners.add(listener);
        listener.accept(current);
        return this;
    }

    public void watch() {
        watcher.scheduleWithFixedDelay(this::checkOnce, CHECK_SECONDS, CHECK_SECONDS, TimeUnit.SECONDS);
        log.info("Слежу за {}: правки подхватываются на ходу", file);
    }

    /** @return true, если файл поправили и настройки переприменены */
    boolean checkOnce() {
        long changedAt = changedAt();
        if (changedAt == seenAt) {
            return false;
        }
        seenAt = changedAt;
        Config reloaded;
        try {
            reloaded = Config.load(file);
        } catch (RuntimeException e) {
            // с испорченным файлом продолжаем на прежних настройках: остановиться
            // из-за опечатки посреди разговора — худшее, что можно сделать
            log.error("Настройки не перечитались, остаюсь на прежних: {}", e.getMessage());
            seenAt = changedAt();
            return false;
        }
        // читая, файл могли дописать недостающими настройками — иначе следующая
        // же проверка увидела бы «правку» и всё началось бы заново
        seenAt = changedAt();
        current = reloaded;
        listeners.forEach(listener -> {
            try {
                listener.accept(reloaded);
            } catch (RuntimeException e) {
                log.warn("Настройка не применилась: {}", e.getMessage());
            }
        });
        log.info("Настройки перечитаны");
        return true;
    }

    private long changedAt() {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        watcher.shutdownNow();
    }
}
