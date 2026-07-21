package ru.rainedev.raine.memory;

import java.util.List;

/** Разбор того, что модель вернула текстом. */
public final class ModelText {

    /**
     * Признаки того, что модель написала вызов инструмента текстом вместо
     * настоящего вызова. Такое нельзя сохранять: разметка осядет в памяти
     * и будет всплывать вперемешку с воспоминаниями.
     */
    private static final List<String> TOOL_CALL_MARKUP =
            List.of("DSML", "tool_calls", "<invoke name=", "<function=");

    private ModelText() {}

    public static boolean looksLikeToolCall(String content) {
        return content != null && TOOL_CALL_MARKUP.stream().anyMatch(content::contains);
    }

    /** Снимает обёртки, которые модель дописывает по своей инициативе. */
    public static String unwrap(String content, String... tags) {
        if (content == null) {
            return "";
        }
        String result = content.replaceAll("```[a-z]*", "");
        for (String tag : tags) {
            result = result.replace("<" + tag + ">", "").replace("</" + tag + ">", "");
        }
        return result.strip();
    }
}
