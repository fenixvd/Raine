package ru.rainedev.raine.core;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.memory.Diary;
import ru.rainedev.raine.memory.DiaryEntry;

/**
 * Позывы написать первой. Человек не ждёт, пока к нему обратятся: он вспоминает
 * что-то своё и пишет сам.
 * <p>
 * Раз в двадцать семь минут — и то не всегда. Ровный интервал читался бы как
 * расписание, а не как порыв.
 */
public final class Spontaneity implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Spontaneity.class);

    private static final Duration INTERVAL = Duration.ofMinutes(27);

    /** Срабатывает примерно в половине случаев. */
    private static final double CHANCE = 0.5;

    private static final String PROMPT = """
            </your_diary_page>

            It's time to reflect on your thoughts!
              - maybe make some reasoning?
              - maybe do some reflection?
              - maybe write to a person and initiate a dialogue? whom you would like to write? maybe call \
            #get_telegram_chats? You can open one chat at a time - choose wisely!
            Act proactively!""";

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("raine-impulse").factory());

    private final NotificationLoop loop;
    private final Diary diary;
    private final Toolbox tools;
    private final RandomGenerator random;

    /** Порыв, который сейчас в очереди или в работе. */
    private final java.util.concurrent.atomic.AtomicReference<Notification> pending =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Пока порыв обрабатывается, список чатов показывается иначе. */
    private final java.util.concurrent.atomic.AtomicBoolean acting =
            new java.util.concurrent.atomic.AtomicBoolean();

    public Spontaneity(NotificationLoop loop, Diary diary, Toolbox tools, RandomGenerator random) {
        this.loop = loop;
        this.diary = diary;
        this.tools = tools;
        this.random = random;
    }

    public boolean isActing() {
        return acting.get();
    }

    public void start() {
        // признак снимается, когда порыв дошёл до обработки и завершился
        loop.onNotificationDone(done -> {
            if (done == pending.get()) {
                pending.set(null);
                acting.set(false);
            }
        });
        scheduler.scheduleAtFixedRate(this::maybeAct,
                INTERVAL.toMinutes(), INTERVAL.toMinutes(), TimeUnit.MINUTES);
        log.info("Спонтанность включена: проверка раз в {} минут", INTERVAL.toMinutes());
    }

    private void maybeAct() {
        try {
            if (random.nextDouble() >= CHANCE) {
                return;
            }
            log.info("Захотелось написать самой");
            acting.set(true);
            Notification impulse = new Notification(buildPrompt(), tools);
            pending.set(impulse);
            loop.submit(impulse);
        } catch (RuntimeException e) {
            log.error("Не удалось создать спонтанный порыв", e);
        }
    }

    /**
     * Поводом становится случайная запись дневника — так же, как человеку
     * что-то вспоминается само и тянет написать.
     */
    private String buildPrompt() {
        StringBuilder prompt = new StringBuilder("<your_diary_page just_for_reasoning no_plagiarism no_copy>\n");
        randomEntry().ifPresent(entry -> prompt.append(entry.body()).append('\n'));
        return prompt.append(PROMPT).toString();
    }

    private java.util.Optional<DiaryEntry> randomEntry() {
        List<Diary.Match> all = diary.query(new double[0]);
        if (all.isEmpty()) {
            return java.util.Optional.empty();
        }
        // запись забирается из выдачи: иначе порыв за порывом крутился бы
        // вокруг одного и того же воспоминания
        return java.util.Optional.of(diary.take(all.get(random.nextInt(all.size()))));
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
