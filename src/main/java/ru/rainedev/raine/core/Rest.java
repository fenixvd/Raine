package ru.rainedev.raine.core;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Уходит в офлайн, как человек, у которого своя жизнь.
 * <p>
 * Смысл не в экономии: непрерывная доступность выдаёт программу. Живой
 * собеседник иногда отходит, и сообщения за это время накапливаются, а потом
 * читаются разом. Сообщение владельца будит — как звонок посреди дня.
 */
public class Rest {

    private static final Logger log = LoggerFactory.getLogger(Rest.class);

    /** Насколько вероятно отойти перед очередным уведомлением. */
    private static final double CHANCE = 0.01;

    private static final int MIN_MINUTES = 15;
    private static final int MAX_MINUTES = 120;

    /** Шаг проверки пробуждения — реагировать надо быстро, а не досыпать час. */
    private static final Duration TICK = Duration.ofSeconds(1);

    private final RandomGenerator random;
    private NightSleep night;

    /** Дневные отлучки можно выключить: остаётся только ночной сон. */
    private boolean dayNaps = true;

    /**
     * Чем заняться ночью. Получает отведённое время и признак пробуждения.
     * <p>
     * Именно ночью, а не в любую отлучку: пересмотр памяти необратимо
     * переписывает накопленное, и запускать его на пятнадцатиминутной
     * передышке среди дня — значит перекраивать дневник по нескольку раз
     * в сутки.
     */
    private java.util.function.BiConsumer<Duration, java.util.function.BooleanSupplier> duringNight = (d, w) -> { };

    /** Уснула или проснулась — по этому меняется статус «в сети». */
    private java.util.function.Consumer<Boolean> onStateChange = resting -> { };
    private final AtomicBoolean awoken = new AtomicBoolean();
    private volatile boolean resting;

    /** Пора закрываться: спать больше незачем, и досыпать тем более. */
    private volatile boolean stopping;

    public Rest(RandomGenerator random) {
        this.random = random;
    }

    public void dayNaps(boolean allowed) {
        this.dayNaps = allowed;
    }

    /** Ночь: спит подолгу и в предсказуемое время, в отличие от дневных отлучек. */
    public void night(NightSleep night) {
        this.night = night;
    }

    public void duringNight(java.util.function.BiConsumer<Duration, java.util.function.BooleanSupplier> action) {
        this.duringNight = action;
    }

    public void onStateChange(java.util.function.Consumer<Boolean> action) {
        this.onStateChange = action;
    }

    public boolean isResting() {
        return resting;
    }

    /** Зовётся из другого потока, когда пришло сообщение от владельца. */
    public void wakeUp() {
        if (resting) {
            log.info("Просыпаюсь раньше времени");
        }
        awoken.set(true);
    }

    /** Работа заканчивается: прекратить отдых и больше в него не уходить. */
    public void stop() {
        stopping = true;
    }

    /** Иногда отходит. Возвращается сама или от сообщения владельца. */
    public void maybeRest() {
        if (stopping) {
            return;
        }
        if (night != null) {
            var untilMorning = night.untilMorning();
            if (untilMorning.isPresent()) {
                sleep(untilMorning.get(), "до утра", true);
                return;
            }
        }
        if (!dayNaps || random.nextDouble() >= CHANCE) {
            return;
        }
        sleep(Duration.ofMinutes(MIN_MINUTES + random.nextInt(MAX_MINUTES - MIN_MINUTES + 1)), "ненадолго", false);
    }

    private void sleep(Duration duration, String reason, boolean night) {
        if (duration.isNegative() || duration.isZero()) {
            return;
        }
        if (awoken.getAndSet(false)) {
            // её только что позвали — не время уходить. Сбросить флаг молча нельзя:
            // сообщение владельца оказалось бы потеряно, и она ушла бы спать
            // с непрочитанным
            return;
        }
        log.info("Ухожу {} — на {} мин", reason, duration.toMinutes());

        resting = true;
        onStateChange.accept(true);
        try {
            // ночной сон — не простой: память в это время пересматривается.
            // Дневная передышка на это не годится, она слишком коротка и часта
            if (night) {
                duringNight.accept(duration, awoken::get);
            }
            for (long waited = 0; waited < duration.toSeconds(); waited++) {
                if (awoken.get()) {
                    log.info("Вернулась раньше — меня позвали");
                    return;
                }
                if (stopping) {
                    log.info("Просыпаюсь: пора закрываться");
                    return;
                }
                Thread.sleep(TICK);
            }
            log.info("Вернулась");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            resting = false;
            onStateChange.accept(false);
        }
    }
}
