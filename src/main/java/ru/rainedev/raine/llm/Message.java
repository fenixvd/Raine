package ru.rainedev.raine.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Сообщение в диалоге с моделью. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Message(
        Role role,
        String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId,
        /* deepseek отдаёт размышления отдельным полем */
        @JsonProperty("reasoning_content") String reasoningContent
) {

    public enum Role {
        @JsonProperty("system") SYSTEM,
        @JsonProperty("user") USER,
        @JsonProperty("assistant") ASSISTANT,
        @JsonProperty("tool") TOOL
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null, null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null, null, null);
    }

    /** Её собственная реплика — нужна, когда разговор с моделью идёт в несколько ходов. */
    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null, null, null);
    }

    /** Ответ инструмента — модель связывает его с вызовом по toolCallId. */
    public static Message toolResult(String toolCallId, String content) {
        return new Message(Role.TOOL, content, null, toolCallId, null);
    }

    /** Копия с изменённым текстом — контекст правится дописыванием подсказок. */
    public Message withContent(String newContent) {
        return new Message(role, newContent, toolCalls, toolCallId, reasoningContent);
    }

    public List<ToolCall> toolCallsOrEmpty() {
        return toolCalls == null ? List.of() : toolCalls;
    }
}
