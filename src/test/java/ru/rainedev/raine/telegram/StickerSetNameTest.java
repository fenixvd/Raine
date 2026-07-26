package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Имя пака приходит из переписки в каком угодно виде: человек кидает ссылку,
 * модель пересказывает её своими словами. Telegram же понимает только короткое имя.
 */
class StickerSetNameTest {

    @Test
    void aLinkInAnyShapeBecomesTheShortName() {
        assertEquals("EvilMyQueen", Telegram.stickerSetName("https://t.me/addstickers/EvilMyQueen"));
        assertEquals("EvilMyQueen", Telegram.stickerSetName("t.me/addstickers/EvilMyQueen"));
        assertEquals("EvilMyQueen", Telegram.stickerSetName("  EvilMyQueen  "));
        assertEquals("EvilMyQueen", Telegram.stickerSetName("@EvilMyQueen"));
        assertEquals("EvilMyQueen", Telegram.stickerSetName("https://t.me/addstickers/EvilMyQueen?single"));
    }

    @Test
    void nothingSensibleStaysNothing() {
        assertEquals("", Telegram.stickerSetName(null));
        assertEquals("", Telegram.stickerSetName("   "));
    }
}
