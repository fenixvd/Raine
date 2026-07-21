package ru.rainedev.raine.core;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ошибки, после которых продолжать бессмысленно.
 * <p>
 * Обычная ошибка — повод рассказать о ней модели и жить дальше. Но есть
 * другие: аккаунт заморожен, учётная запись удалена. Продолжать после них —
 * значит раз за разом стучаться в закрытую дверь, копя отказы и привлекая
 * ещё больше внимания. Лучше остановиться сразу и громко.
 */
public final class Fatal {

    private static final Logger log = LoggerFactory.getLogger(Fatal.class);

    private static volatile Consumer<Throwable> onFatal = null;

    private Fatal() {}

    /** Что сделать, когда дальше идти нельзя. Задаётся при запуске. */
    public static void handler(Consumer<Throwable> handler) {
        onFatal = handler;
    }

    /** @return true, если ошибка непоправимая и работа остановлена */
    public static boolean check(Throwable error) {
        if (!isHopeless(error)) {
            return false;
        }
        log.error("Дальше работать нельзя: {}", messageOf(error));
        Consumer<Throwable> handler = onFatal;
        if (handler != null) {
            handler.accept(error);
        }
        return true;
    }

    /**
     * Telegram сообщает об этом текстом ошибки. FROZEN_METHOD_INVALID приходит,
     * когда учётную запись заморозили по жалобам, USER_DEACTIVATED — когда её
     * удалили совсем.
     */
    private static boolean isHopeless(Throwable error) {
        String message = messageOf(error);
        return message.contains("FROZEN_METHOD_INVALID")
                || message.contains("USER_DEACTIVATED")
                || message.contains("AUTH_KEY_UNREGISTERED");
    }

    private static String messageOf(Throwable error) {
        StringBuilder out = new StringBuilder();
        for (Throwable current = error; current != null && out.length() < 4000; current = current.getCause()) {
            if (current.getMessage() != null) {
                out.append(current.getMessage()).append(' ');
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return out.toString();
    }
}
