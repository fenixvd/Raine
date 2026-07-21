package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class SurroundingsTest {

    @Test
    void namesThePartOfDayTheWayPeopleDo() {
        assertEquals("глубокая ночь", Surroundings.partOfDay(LocalTime.of(3, 0)));
        assertEquals("утро", Surroundings.partOfDay(LocalTime.of(8, 0)));
        assertEquals("день", Surroundings.partOfDay(LocalTime.of(14, 0)));
        assertEquals("вечер", Surroundings.partOfDay(LocalTime.of(20, 0)));
        assertEquals("ночь", Surroundings.partOfDay(LocalTime.of(23, 30)));
    }

    @Test
    void weatherCodesBecomeWordsNotNumbers() {
        assertEquals("ясно", Surroundings.describe(0));
        assertEquals("дождь", Surroundings.describe(61));
        assertEquals("снег", Surroundings.describe(73));
        assertEquals("гроза", Surroundings.describe(95));
    }

    @Test
    void unknownWeatherCodeDoesNotBreakAnything() {
        assertEquals("непонятная погода", Surroundings.describe(12345));
    }

    @Test
    void disabledSurroundingsAddNothingToPrompt() {
        assertEquals("", new Surroundings(false, 55.75, 37.62).asPromptSuffix());
    }

    @Test
    void promptMentionsTimeOfDay() {
        // погоду в тесте не спрашиваем — важно, что время суток есть всегда
        String suffix = new Surroundings(true, 0, 0).asPromptSuffix();

        assertTrue(suffix.contains("<around_you>"), suffix);
        assertTrue(suffix.contains("Сейчас"), suffix);
    }
}
