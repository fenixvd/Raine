package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Random;
import org.junit.jupiter.api.Test;

class NightSleepTest {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private static Clock at(String moment) {
        return Clock.fixed(Instant.parse(moment), MSK);
    }

    private static NightSleep night(Clock clock) {
        return new NightSleep(LocalTime.of(23, 0), LocalTime.of(1, 0),
                LocalTime.of(6, 0), LocalTime.of(9, 0), clock, new Random(7));
    }

    @Test
    void staysAwakeDuringTheDay() {
        // 15:00 по Москве
        assertTrue(night(at("2026-07-21T12:00:00Z")).untilMorning().isEmpty());
    }

    @Test
    void sleepsDeepInTheNight() {
        // 03:00 по Москве — точно между отбоем и подъёмом
        var remaining = night(at("2026-07-22T00:00:00Z")).untilMorning();

        assertTrue(remaining.isPresent(), "в три часа ночи она должна спать");
        assertTrue(remaining.get().compareTo(Duration.ofHours(6)) < 0, "спать осталось не всю ночь: " + remaining.get());
    }

    @Test
    void isAwakeAgainInTheMorning() {
        // 11:00 по Москве — давно проснулась
        assertTrue(night(at("2026-07-22T08:00:00Z")).untilMorning().isEmpty());
    }

    @Test
    void bedtimeAndWakeFallInsideTheirWindows() {
        for (int seed = 0; seed < 50; seed++) {
            NightSleep night = new NightSleep(LocalTime.of(23, 0), LocalTime.of(1, 0),
                    LocalTime.of(6, 0), LocalTime.of(9, 0), at("2026-07-21T19:00:00Z"), new Random(seed));

            LocalTime bedtime = night.bedtime().toLocalTime();
            LocalTime wake = night.wakeTime().toLocalTime();

            assertTrue(bedtime.isAfter(LocalTime.of(22, 59)) || bedtime.isBefore(LocalTime.of(1, 1)),
                    "легла в неурочное время: " + bedtime);
            assertTrue(!wake.isBefore(LocalTime.of(6, 0)) && !wake.isAfter(LocalTime.of(9, 0)),
                    "встала в неурочное время: " + wake);
        }
    }

    @Test
    void scheduleDoesNotShiftWhileTheNightGoesOn() {
        // иначе расписание менялось бы при каждой проверке, и сон не кончился бы никогда
        NightSleep night = night(at("2026-07-22T00:00:00Z"));

        assertEquals(night.wakeTime(), night.wakeTime());
        assertEquals(night.bedtime(), night.bedtime());
    }

    @Test
    void nightAfterMidnightBelongsToThePreviousEvening() {
        // в час ночи идёт ночь вчерашнего дня, иначе в полночь расписание
        // перевыбиралось бы прямо посреди сна
        NightSleep night = night(at("2026-07-21T22:30:00Z"));

        assertTrue(night.wakeTime().toLocalDate().isAfter(night.bedtime().toLocalDate())
                        || night.bedtime().toLocalTime().isBefore(LocalTime.of(1, 1)),
                "подъём должен быть на следующее утро после отбоя");
    }

    @Test
    void wakeTimeIsAlwaysAfterBedtime() {
        for (int seed = 0; seed < 50; seed++) {
            NightSleep night = new NightSleep(LocalTime.of(23, 0), LocalTime.of(1, 0),
                    LocalTime.of(6, 0), LocalTime.of(9, 0), at("2026-07-21T20:00:00Z"), new Random(seed));

            assertFalse(night.wakeTime().isBefore(night.bedtime()),
                    "проснуться раньше, чем легла, нельзя: " + night.bedtime() + " → " + night.wakeTime());
        }
    }
}
