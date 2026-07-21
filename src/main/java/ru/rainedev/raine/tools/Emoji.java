package ru.rainedev.raine.tools;

import java.util.Set;

/** Эмодзи-реакции: Telegram принимает их в строго определённом виде. */
public final class Emoji {

    /** Вариационный селектор — невидимый символ, из-за которого реакция отклоняется. */
    private static final String VARIATION_SELECTOR = "️";

    /** Набор, разрешённый Telegram в обычных чатах. */
    public static final Set<String> ALLOWED = Set.of(
            "👍", "👎", "❤", "🔥", "🥰", "👏", "😁", "🤔", "🤯", "😱", "🤬", "😢", "🎉", "🤩", "🤮", "💩",
            "🙏", "👌", "🕊", "🤡", "🥱", "🥴", "😍", "🐳", "🌚", "🌭", "💯", "🤣", "⚡", "🍌", "🏆", "💔",
            "🤨", "😐", "🍓", "🍾", "💋", "😈", "😴", "😭", "🤓", "👻", "👀", "🎃", "😇", "😨", "🤝", "🤗",
            "🎅", "💅", "🤪", "🗿", "🆒", "💘", "🦄", "😘", "💊", "😎", "👾", "🤷", "😡");

    private Emoji() {}

    /**
     * Telegram хранит активные реакции без вариационного селектора: сердце — это
     * U+2764, а не U+2764 U+FE0F. Модель почти всегда присылает вторую форму,
     * и без очистки реакция отклоняется.
     */
    public static String normalize(String emoji) {
        return emoji == null ? "" : emoji.replace(VARIATION_SELECTOR, "").strip();
    }

    public static boolean isAllowed(String emoji) {
        return ALLOWED.contains(normalize(emoji));
    }
}
