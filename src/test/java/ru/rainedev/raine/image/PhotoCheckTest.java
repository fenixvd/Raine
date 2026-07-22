package ru.rainedev.raine.image;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PhotoCheckTest {

    @Test
    void explicitWishIsRecognised() {
        assertTrue(ImageGenerator.isExplicit("Raine takes a selfie, explicit nudity"));
        assertTrue(ImageGenerator.isExplicit("SELFIE, NSFW, on the bed"));
        assertFalse(ImageGenerator.isExplicit("Raine makes a playful selfie by the window"));
        assertFalse(ImageGenerator.isExplicit(null));
    }

    @Test
    void refusalIsNotADefect() {
        // «я не могу это смотреть» — отказ проверять, а не изъян снимка: принять
        // его за брак значит выбросить готовую картинку и встать в очередь заново
        assertTrue(ImageGenerator.isRefusal("I'm sorry, I can't assist with that."));
        assertTrue(ImageGenerator.isRefusal("К сожалению, я не могу описать это изображение"));
        assertFalse(ImageGenerator.isRefusal("VERDICT: BAD\nFEEDBACK: six fingers on the left hand"));
        assertFalse(ImageGenerator.isRefusal("VERDICT: OK"));
    }
}
