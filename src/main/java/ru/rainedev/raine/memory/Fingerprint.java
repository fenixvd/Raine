package ru.rainedev.raine.memory;

/**
 * Короткий отпечаток текста — чтобы узнавать одно и то же в разных местах.
 * <p>
 * Раньше «эта запись уже в контексте?» решалось поиском подстроки по всему
 * разговору. На длинном контексте это перебор всего текста на каждую запись
 * дневника, и он же ошибается: запись, целиком вошедшая в чью-то цитату,
 * считается «уже вспомненной». Отпечаток берётся один раз и сравнивается
 * за постоянное время.
 */
public final class Fingerprint {

    private Fingerprint() {}

    /**
     * Пробелы и регистр не в счёт: одна и та же мысль, перенесённая по-другому,
     * должна давать один отпечаток.
     */
    public static long of(String text) {
        if (text == null) {
            return 0;
        }
        StringBuilder normalised = new StringBuilder(text.length());
        boolean lastWasSpace = true;
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace) {
                    normalised.append(' ');
                }
                lastWasSpace = true;
            } else {
                normalised.append(c);
                lastWasSpace = false;
            }
        }
        return normalised.toString().strip().hashCode() & 0xffffffffL;
    }

}
