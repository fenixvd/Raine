package ru.rainedev.raine.core;

import java.util.List;
import ru.rainedev.raine.llm.ToolCall;

/**
 * Что модель решила сделать на этом шаге — целиком, а не по одному вызову.
 * <p>
 * Инструменту иногда важно знать, один ли он в ходе. Отправка одной длинной
 * реплики и отправка трёх коротких подряд выглядят одинаково изнутри
 * отдельного вызова, а придираться стоит только к первой. Открыть же два
 * разных чата за один шаг нельзя вовсе: это верный признак путаницы.
 * <p>
 * Ход обрабатывается на одном потоке от начала до конца, поэтому список
 * живёт рядом с ним и не смешивается с чужими.
 */
public final class CurrentStep {

    private static final ThreadLocal<List<ToolCall>> CALLS = ThreadLocal.withInitial(List::of);

    private CurrentStep() {}

    static void set(List<ToolCall> calls) {
        CALLS.set(calls == null ? List.of() : calls);
    }

    static void clear() {
        CALLS.remove();
    }

    /** Сколько раз этот инструмент вызван на текущем шаге. */
    public static long countOf(String toolName) {
        return CALLS.get().stream().filter(call -> toolName.equals(call.name())).count();
    }

    public static List<ToolCall> calls() {
        return CALLS.get();
    }
}
