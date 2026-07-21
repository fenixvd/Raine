package ru.rainedev.raine.memory;

import java.util.List;
import ru.rainedev.raine.llm.Message;

/** Источник воспоминаний для контекста. Отдельный интерфейс — чтобы цикл не знал про эмбеддинги. */
@FunctionalInterface
public interface Memory {

    /**
     * @param recentContext последние сообщения — по ним понятно, о чём сейчас речь
     * @return текст воспоминаний для подмешивания или пустая строка
     */
    String recall(List<Message> recentContext);

    /** Память отключена — Raine помнит только текущий разговор. */
    Memory NONE = context -> "";
}
