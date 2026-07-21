package ru.rainedev.raine.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.config.Config;

/**
 * Ходит в настоящий эндпоинт и тратит токены, поэтому по умолчанию исключён.
 * Запуск: ./gradlew integrationTest
 */
@Tag("integration")
class OpenAiCompatibleClientIntegrationTest {

    private final Config config = Config.load();
    private final LlmClient client = new OpenAiCompatibleClient(config.llm(), "openai/text-embedding-3-large");

    @Test
    void callsToolInsteadOfAnsweringWithText() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode tools = (ArrayNode) mapper.readTree("""
                [{
                  "type": "function",
                  "function": {
                    "name": "send_telegram_message",
                    "description": "Отправляет сообщение собеседнику.",
                    "parameters": {
                      "type": "object",
                      "properties": {
                        "text": {"type": "string", "description": "Текст сообщения"}
                      },
                      "required": ["text"]
                    }
                  }
                }]""");

        ChatResponse response = client.chat(
                "Ты общаешься исключительно вызовом инструментов. Обычный текст никто не увидит.",
                List.of(Message.user("Тебе написали: «привет!». Ответь собеседнику.")),
                tools);

        assertFalse(response.toolCalls().isEmpty(), "модель обязана вызвать инструмент, а не писать текстом");
        assertEquals("send_telegram_message", response.toolCalls().getFirst().name());
        assertTrue(response.totalTokens() > 0, "эндпоинт должен сообщать расход токенов");
    }

    @Test
    void returnsEmbeddingVector() {
        double[] vector = client.embedding("проверка связи");

        assertTrue(vector.length > 100, "вектор подозрительно короткий: " + vector.length);
    }
}
