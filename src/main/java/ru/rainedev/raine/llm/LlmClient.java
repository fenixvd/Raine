package ru.rainedev.raine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Клиент к языковой модели. Отдельный интерфейс — чтобы в тестах подменять заглушкой. */
public interface LlmClient {

    /**
     * @param systemPrompt системный промпт целиком
     * @param history      диалог; последним обычно идёт уведомление
     * @param tools        описания инструментов в формате OpenAI, может быть пустым
     */
    ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools);

    /** Вектор для поиска по дневнику. */
    double[] embedding(String input);

    /**
     * Описывает картинку словами.
     *
     * @param model какой моделью смотреть — дешёвой или основной
     */
    default String describeImage(String model, String systemPrompt, String context, byte[] image) {
        throw new UnsupportedOperationException("Зрение не подключено");
    }
}
