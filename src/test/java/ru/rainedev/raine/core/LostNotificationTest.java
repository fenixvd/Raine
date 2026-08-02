package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;

/**
 * Пропавшая сеть стоила ей целого сообщения: клиент отрабатывал три попытки,
 * дальше уведомление вылетало с ошибкой и никто к нему не возвращался.
 * Найдено в логах сервера за 31.07.
 */
class LostNotificationTest {

    /** Первые несколько раз изображает обрыв сети, потом отвечает как обычно. */
    private static final class FlakyNetwork implements LlmClient {
        private final int failures;
        private int calls;
        private List<Message> lastContext = List.of();

        FlakyNetwork(int failures) {
            this.failures = failures;
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            calls++;
            if (calls <= failures) {
                throw new UncheckedIOException("Сеть недоступна", new IOException("connect"));
            }
            lastContext = List.copyOf(history);
            Message message = new Message(Message.Role.ASSISTANT, "",
                    List.of(new ToolCall("call_wait", "function", new ToolCall.Function("wait", "{}"))), null, null);
            return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, message, "tool_calls")),
                    new ChatResponse.Usage(10, 0, 10));
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {0};
        }
    }

    private static Thread start(NotificationLoop loop) {
        Thread thread = Thread.ofVirtual().start(loop::run);
        return thread;
    }

    @Test
    void notificationSurvivesNetworkOutage() throws Exception {
        FlakyNetwork llm = new FlakyNetwork(2);
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(20));
        loop.retryPause(Duration.ofMillis(20));

        CountDownLatch done = new CountDownLatch(1);
        loop.onNotificationDone(notification -> done.countDown());

        Thread worker = start(loop);
        loop.submit(new Notification("Серёжа написал: привет"));

        assertTrue(done.await(5, TimeUnit.SECONDS), "уведомление так и не было обработано");
        worker.interrupt();

        assertEquals(3, llm.calls, "две попытки должны были упереться в сеть, третья — пройти");
        long copies = llm.lastContext.stream()
                .filter(m -> m.content() != null && m.content().contains("Серёжа написал: привет"))
                .count();
        assertEquals(1, copies, "после неудачной попытки сообщение не должно оставаться в контексте дважды");
    }

    @Test
    void hopelessOutageGivesUpInsteadOfSpinning() throws Exception {
        // сеть не вернулась совсем: важно, что цикл не встанет намертво на одном уведомлении
        FlakyNetwork llm = new FlakyNetwork(Integer.MAX_VALUE);
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(20));
        loop.retryPause(Duration.ofMillis(10));

        Thread worker = start(loop);
        loop.submit(new Notification("тебе написали"));

        Thread.sleep(600);
        worker.interrupt();

        assertTrue(llm.calls >= 6, "попыток должно быть несколько, а вышло " + llm.calls);
        assertTrue(llm.calls < 40, "цикл не должен долбиться в стену без остановки: " + llm.calls);
        assertEquals(0, loop.queued(), "безнадёжное уведомление не должно остаться в очереди навсегда");
    }
}
