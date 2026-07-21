package ru.rainedev.raine.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Запрос модели на вызов инструмента. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolCall(String id, String type, Function function) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Function(String name, String arguments) {}

    public String name() {
        return function == null ? "" : function.name();
    }

    public String arguments() {
        return function == null || function.arguments() == null ? "{}" : function.arguments();
    }
}
