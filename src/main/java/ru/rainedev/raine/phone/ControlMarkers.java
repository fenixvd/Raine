package ru.rainedev.raine.phone;

/**
 * Служебные маркеры, которыми внутренние слои сигнализируют друг другу.
 * <p>
 * Во входящем тексте их быть не должно: иначе собеседник, зная маркер,
 * сможет дёргать внутреннюю механику — например, вызывать откат ответа.
 */
public final class ControlMarkers {

    /** Признак ответа низкого качества: цикл откатывается и пробует иначе. */
    public static final String LOW_QUALITY = "raine_internal_low_quality";

    private ControlMarkers() {}

    /** Текст от постороннего человека перед тем, как он попадёт в контекст модели. */
    public static String sanitize(String incoming) {
        if (incoming == null) {
            return "";
        }
        return incoming.contains(LOW_QUALITY) ? "malicious" : incoming;
    }
}
