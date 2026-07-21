package ru.rainedev.raine.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.rainedev.raine.llm.ToolCall;

/**
 * Набор инструментов, доступных прямо сейчас.
 * <p>
 * Состав меняется по ходу дела: пока чат не открыт, отправлять нечего,
 * а в канале отправка недоступна вовсе. Ограничение правами, а не просьбой
 * в промпте: инструмент, которого нет, нельзя уговорить появиться.
 */
public final class Toolbox {

    /** Ими модель завершает ход: «мне больше нечего делать». */
    public static final Set<String> FINISHING = Set.of("wait", "pause");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public Toolbox(Tool... initial) {
        for (Tool tool : initial) {
            add(tool);
        }
    }

    public Toolbox add(Tool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    public Set<String> names() {
        return tools.keySet();
    }

    public java.util.Collection<Tool> all() {
        return tools.values();
    }

    public ArrayNode asJson() {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        tools.values().forEach(tool -> array.add(tool.asJson()));
        return array;
    }

    /**
     * Выполняет вызовы по порядку и возвращает ответы для контекста.
     * <p>
     * Технические ошибки не роняют ход: текст ошибки отдаётся модели, и она
     * решает, что делать дальше. {@link LowQualityException} пробрасывается —
     * его обрабатывает цикл.
     */
    public List<ru.rainedev.raine.llm.Message> invoke(List<ToolCall> calls) {
        List<ru.rainedev.raine.llm.Message> results = new ArrayList<>();
        for (ToolCall call : calls) {
            results.add(ru.rainedev.raine.llm.Message.toolResult(call.id(), clean(invokeOne(call))));
        }
        return results;
    }

    private String invokeOne(ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return "Инструмент '%s' сейчас недоступен. Доступны: %s".formatted(call.name(), String.join(", ", names()));
        }
        try {
            // инструменты, появившиеся по ходу дела, живут в этом же наборе
            // и исчезнут вместе с ним
            return tool.handler().call(parse(call.arguments()), this::add);
        } catch (LowQualityException e) {
            throw e;
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return "Не получилось: " + reason;
        }
    }

    /**
     * Управляющие символы в результате инструмента модели ни к чему: они приходят
     * из чужих текстов и имён файлов, а в контексте выглядят как мусор.
     */
    private static String clean(String result) {
        if (result == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(result.length());
        result.codePoints().forEach(c -> {
            if (c == '\n' || c == '\t' || c >= 0x20) {
                out.appendCodePoint(c);
            }
        });
        return out.toString();
    }

    private static JsonNode parse(String arguments) {
        try {
            return MAPPER.readTree(arguments);
        } catch (Exception e) {
            // модель иногда присылает пустую строку или мусор вместо JSON
            return JsonNodeFactory.instance.objectNode();
        }
    }
}
