package ru.rainedev.raine.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

/** Ответ OpenAI-совместимого эндпоинта. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatResponse(String id, String model, List<Choice> choices, Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(int index, Message message, @JsonProperty("finish_reason") String finishReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") long promptTokens,
            @JsonProperty("completion_tokens") long completionTokens,
            @JsonProperty("total_tokens") long totalTokens) {

        public static final Usage EMPTY = new Usage(0, 0, 0);
    }

    public Optional<Message> firstMessage() {
        return choices == null || choices.isEmpty()
                ? Optional.empty()
                : Optional.ofNullable(choices.getFirst().message());
    }

    /** Инструменты, которые модель просит вызвать. Пустой список — она не сделала ничего. */
    public List<ToolCall> toolCalls() {
        return firstMessage().map(Message::toolCallsOrEmpty).orElseGet(List::of);
    }

    public String text() {
        return firstMessage().map(Message::content).orElse("");
    }

    public long totalTokens() {
        return usage == null ? 0 : usage.totalTokens();
    }
}
