package ru.rainedev.raine.phone;

import java.time.Duration;
import java.time.Instant;

/**
 * Давность словами — так, как её показывает список чатов в мессенджере.
 * <p>
 * Единственное число пишется словом («1 minute ago», а не «1 minutes ago»),
 * а совсем свежее — «just now»: разница между «минуту назад» и «только что»
 * для решения, отвечать ли сейчас, важнее, чем точность в секундах.
 */
public final class TimeAgo {

    private TimeAgo() {}

    /** @param unixSeconds время события в секундах эпохи */
    public static String of(long unixSeconds) {
        return of(Duration.between(Instant.ofEpochSecond(unixSeconds), Instant.now()));
    }

    public static String of(Duration passed) {
        long minutes = passed.toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return plural(minutes, "minute");
        }
        long hours = passed.toHours();
        if (hours < 24) {
            return plural(hours, "hour");
        }
        long days = hours / 24;
        if (days < 7) {
            return plural(days, "day");
        }
        return plural(days / 7, "week");
    }

    private static String plural(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s") + " ago";
    }
}
