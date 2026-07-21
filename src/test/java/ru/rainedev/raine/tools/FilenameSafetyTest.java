package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class FilenameSafetyTest {

    private static boolean plain(String name) throws Exception {
        Method method = TelegramTools.class.getDeclaredMethod("isPlainFilename", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, name);
    }

    @Test
    void acceptsOrdinaryFilenames() throws Exception {
        assertTrue(plain("1784651739.webp"));
        assertTrue(plain("1784651739.ogg"));
    }

    @Test
    void refusesPathsLeadingOutOfTheFolder() throws Exception {
        // имя файла приходит от модели: без проверки это дорога к любому файлу на диске
        assertFalse(plain("../../etc/passwd"));
        assertFalse(plain("..\\windows\\system32"));
        assertFalse(plain("data/gallery/photo.webp"));
        assertFalse(plain("/etc/shadow"));
        assertFalse(plain(".."));
    }

    @Test
    void refusesEmptyName() throws Exception {
        assertFalse(plain(""));
    }
}
