package ru.rainedev.raine.phone;

import it.tdlight.jni.TdApi;

/**
 * Описывает вложение словами. Отдельный интерфейс, потому что разметка
 * сообщений — чистое преобразование, а разглядывание картинки и распознавание
 * голоса ходят в сеть.
 */
@FunctionalInterface
public interface MediaDescriber {

    /** @return описание для подстановки под вложение или пустая строка */
    String describe(TdApi.Message message);

    /** Восприятие не подключено: видно только тип вложения. */
    MediaDescriber NONE = message -> "";
}
