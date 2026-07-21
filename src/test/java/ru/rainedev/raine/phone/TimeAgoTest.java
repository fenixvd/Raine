package ru.rainedev.raine.phone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TimeAgoTest {

    @Test
    void freshEventIsJustNow() {
        assertEquals("just now", TimeAgo.of(Duration.ofSeconds(59)));
    }

    @Test
    void singularHasNoTrailingS() {
        assertEquals("1 minute ago", TimeAgo.of(Duration.ofMinutes(1)));
        assertEquals("1 hour ago", TimeAgo.of(Duration.ofHours(1)));
        assertEquals("1 day ago", TimeAgo.of(Duration.ofDays(1)));
        assertEquals("1 week ago", TimeAgo.of(Duration.ofDays(7)));
    }

    @Test
    void switchesUnitsAtTheRightMoment() {
        assertEquals("59 minutes ago", TimeAgo.of(Duration.ofMinutes(59)));
        assertEquals("23 hours ago", TimeAgo.of(Duration.ofHours(23)));
        assertEquals("6 days ago", TimeAgo.of(Duration.ofDays(6)));
        assertEquals("2 weeks ago", TimeAgo.of(Duration.ofDays(15)));
    }

    @Test
    void acceptsUnixSeconds() {
        assertEquals("just now", TimeAgo.of(System.currentTimeMillis() / 1000));
    }
}
