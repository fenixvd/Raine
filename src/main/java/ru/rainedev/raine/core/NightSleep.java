package ru.rainedev.raine.core;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Ночь. Человек не сидит в переписке круглосуточно: он ложится ближе к полуночи
 * и встаёт утром, а сообщения, пришедшие ночью, читает после пробуждения.
 * <p>
 * Время отхода ко сну и подъёма выбирается на каждую ночь заново в пределах
 * окна: ровно в 23:00 каждый день ложится расписание, а не живой человек.
 */
public final class NightSleep {

    /** Между этими часами ложится. Позже полуночи — это уже следующие сутки. */
    private final LocalTime bedtimeFrom;
    private final LocalTime bedtimeTo;

    /** Между этими часами встаёт. */
    private final LocalTime wakeFrom;
    private final LocalTime wakeTo;

    private final Clock clock;
    private final RandomGenerator random;

    /** Расписание держится на сутки, иначе оно менялось бы при каждой проверке. */
    private LocalDate plannedFor;
    private LocalDateTime bedtime;
    private LocalDateTime wakeTime;

    public NightSleep(LocalTime bedtimeFrom, LocalTime bedtimeTo, LocalTime wakeFrom, LocalTime wakeTo,
                      Clock clock, RandomGenerator random) {
        this.bedtimeFrom = bedtimeFrom;
        this.bedtimeTo = bedtimeTo;
        this.wakeFrom = wakeFrom;
        this.wakeTo = wakeTo;
        this.clock = clock;
        this.random = random;
    }

    /** @return сколько осталось спать, если сейчас ночь */
    public Optional<Duration> untilMorning() {
        LocalDateTime now = LocalDateTime.now(clock);
        plan(now);

        if (now.isBefore(bedtime) || !now.isBefore(wakeTime)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, wakeTime));
    }

    public LocalDateTime bedtime() {
        plan(LocalDateTime.now(clock));
        return bedtime;
    }

    public LocalDateTime wakeTime() {
        plan(LocalDateTime.now(clock));
        return wakeTime;
    }

    /**
     * Ночь считается по дате отхода ко сну. Если сейчас час ночи, значит идёт
     * ночь предыдущих суток — иначе в полночь расписание менялось бы посреди сна.
     */
    private void plan(LocalDateTime now) {
        LocalDate night = now.toLocalTime().isBefore(wakeTo) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        if (night.equals(plannedFor)) {
            return;
        }
        plannedFor = night;
        bedtime = LocalDateTime.of(night, pick(bedtimeFrom, bedtimeTo));
        if (bedtimeTo.isBefore(bedtimeFrom) && bedtime.toLocalTime().isBefore(bedtimeFrom)) {
            bedtime = bedtime.plusDays(1);   // легла уже после полуночи
        }
        wakeTime = LocalDateTime.of(night.plusDays(1), pick(wakeFrom, wakeTo));
    }

    private LocalTime pick(LocalTime from, LocalTime to) {
        int start = from.toSecondOfDay();
        int end = to.toSecondOfDay();
        int span = end >= start ? end - start : (86400 - start) + end;
        return LocalTime.ofSecondOfDay((start + random.nextInt(Math.max(1, span))) % 86400);
    }
}
