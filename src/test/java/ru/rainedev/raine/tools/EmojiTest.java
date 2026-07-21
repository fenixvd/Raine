package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmojiTest {

    @Test
    void stripsVariationSelector() {
        // Telegram хранит реакции без него, и с ним реакция отклоняется
        String withSelector = "❤️";

        assertEquals("❤", Emoji.normalize(withSelector));
        assertEquals(1, Emoji.normalize(withSelector).codePointCount(0, Emoji.normalize(withSelector).length()));
    }

    @Test
    void acceptsBothFormsOfHeart() {
        assertTrue(Emoji.isAllowed("❤️"), "форма с селектором — та, что чаще всего присылает модель");
        assertTrue(Emoji.isAllowed("❤"));
    }

    @Test
    void acceptsLightningInBothForms() {
        assertTrue(Emoji.isAllowed("⚡️"));
        assertTrue(Emoji.isAllowed("⚡"));
    }

    @Test
    void singleCodepointEmojiWorkAsIs() {
        assertTrue(Emoji.isAllowed("🔥"));
        assertTrue(Emoji.isAllowed("🤔"));
        assertTrue(Emoji.isAllowed("👍"));
    }

    @Test
    void rejectsEmojiOutsideTheAllowedSet() {
        assertFalse(Emoji.isAllowed("🍕"));
        assertFalse(Emoji.isAllowed("не эмодзи"));
        assertFalse(Emoji.isAllowed(null));
    }

    @Test
    void trimsAccidentalWhitespace() {
        assertTrue(Emoji.isAllowed("  🔥 "));
    }
}
