package ru.rainedev.raine.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Инструмент, доступный модели. Действовать она может только через них. */
public record Tool(String name, String description, ObjectNode parameters, ContextualHandler handler) {

    /**
     * Обработчик, которому нужен набор инструментов текущего хода. Открытие чата
     * добавляет в него отправку — и она обязана исчезнуть вместе с этим ходом,
     * иначе сообщение уйдёт в чат, открытый когда-то раньше.
     */
    @FunctionalInterface
    public interface ContextualHandler {
        String call(JsonNode arguments, java.util.function.Consumer<Tool> addTool);
    }

    @FunctionalInterface
    public interface Handler {
        /**
         * @return текст результата — его увидит модель
         * @throws LowQualityException если результат вышел плохим и стоит попробовать иначе
         */
        String call(JsonNode arguments);
    }

    /** Описание в формате, который понимает OpenAI-совместимый эндпоинт. */
    public ObjectNode asJson() {
        ObjectNode function = JsonNodeFactory.instance.objectNode();
        function.put("name", name);
        function.put("description", description);
        function.set("parameters", parameters);

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("type", "function");
        root.set("function", function);
        return root;
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    /** Инструмент без параметров — например, «подождать». */
    public static Tool simple(String name, String description, Handler handler) {
        return named(name).describedAs(description).build(handler);
    }

    /** Вызов без набора инструментов — для тестов и простых случаев. */
    public String call(JsonNode arguments) {
        return handler.call(arguments, added -> { });
    }

    public static final class Builder {

        private final String name;
        private String description = "";
        private final Map<String, ObjectNode> properties = new LinkedHashMap<>();
        private final java.util.List<String> required = new java.util.ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder describedAs(String description) {
            this.description = description;
            return this;
        }

        public Builder requiredString(String property, String description) {
            return property(property, "string", description, true);
        }

        public Builder optionalString(String property, String description) {
            return property(property, "string", description, false);
        }

        public Builder requiredInteger(String property, String description) {
            return property(property, "integer", description, true);
        }

        /** С булевыми параметрами модели справляются надёжнее, чем со строкой «true». */
        public Builder optionalBoolean(String property, String description) {
            return property(property, "boolean", description, false);
        }

        private Builder property(String property, String type, String description, boolean isRequired) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("type", type);
            node.put("description", description);
            properties.put(property, node);
            if (isRequired) {
                required.add(property);
            }
            return this;
        }

        public Tool build(Handler handler) {
            return buildContextual((arguments, addTool) -> handler.call(arguments));
        }

        /** Для инструментов, которые открывают доступ к другим инструментам. */
        public Tool buildContextual(ContextualHandler handler) {
            ObjectNode parameters = JsonNodeFactory.instance.objectNode();
            parameters.put("type", "object");
            ObjectNode props = parameters.putObject("properties");
            properties.forEach(props::set);
            parameters.putArray("required").addAll(required.stream().map(JsonNodeFactory.instance::textNode).toList());
            parameters.put("additionalProperties", false);
            return new Tool(name, description, parameters, handler);
        }
    }
}
