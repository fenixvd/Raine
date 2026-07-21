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
            @JsonProperty("total_tokens") long totalTokens,
            @JsonProperty("prompt_tokens_details") PromptDetails promptDetails) {

        public static final Usage EMPTY = new Usage(0, 0, 0, null);

        public Usage(long promptTokens, long completionTokens, long totalTokens) {
            this(promptTokens, completionTokens, totalTokens, null);
        }
    }

    /**
     * Подробности про подсказку. Интересно ровно одно: попала ли она в кэш.
     * Если посредник перестал кэшировать, каждый шаг оплачивается как первый,
     * и заметить это иначе можно только по счёту в конце месяца.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromptDetails(
            @JsonProperty("cached_tokens") long cachedTokens,
            @JsonProperty("cache_write_tokens") Long cacheWriteTokens) {}

    /** @return true, если посредник прямо сообщил, что в кэш ничего не записал */
    public boolean cacheLooksBroken() {
        return usage != null && usage.promptDetails() != null
                && usage.promptDetails().cacheWriteTokens() != null
                && usage.promptDetails().cacheWriteTokens() == 0
                && usage.promptDetails().cachedTokens() == 0;
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
