package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;

class GracefulStopTest {

    /** Модель, которая отвечает долго — как настоящая. */
    private record SlowLlm(CountDownLatch started, Duration thinking) implements LlmClient {
        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            started.countDown();
            try {
                Thread.sleep(thinking);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Message answer = new Message(Message.Role.ASSISTANT, null,
                    List.of(new ToolCall("1", "function", new ToolCall.Function("wait", "{}"))), null, null);
            return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, answer, "stop")),
                    ChatResponse.Usage.EMPTY);
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {1};
        }
    }

    private static Toolbox finishing() {
        return new Toolbox(Tool.simple("wait", "", arguments -> "ok"));
    }

    @Test
    void startedTurnIsAllowedToFinish() throws InterruptedException {
        // оборвать посреди хода — значит потерять разговор целиком:
        // он живёт в памяти до самой записи в дневник
        CountDownLatch thinking = new CountDownLatch(1);
        NotificationLoop loop = new NotificationLoop(
                new SlowLlm(thinking, Duration.ofMillis(300)), () -> "промпт", finishing(), 40_000);
        loop.idleCheck(Duration.ofMillis(20));

        Thread brain = Thread.ofVirtual().start(loop::run);
        loop.submit(new Notification("сообщение", finishing()));
        assertTrue(thinking.await(2, TimeUnit.SECONDS), "ход должен начаться");

        loop.stop();

        assertTrue(loop.awaitStopped(Duration.ofSeconds(5)), "должна закончить сама");
        assertTrue(loop.context().size() >= 2, "сказанное за ход остаётся на месте — его ещё сохранять");
        brain.interrupt();
    }

    @Test
    void stopWakesHerFromSilence() {
        NotificationLoop loop = new NotificationLoop(
                new SlowLlm(new CountDownLatch(1), Duration.ZERO), () -> "промпт", finishing(), 40_000);
        loop.idleCheck(Duration.ofMinutes(10));   // в тишине она ждала бы очень долго

        Thread brain = Thread.ofVirtual().start(loop::run);
        loop.stop();

        assertTrue(loop.awaitStopped(Duration.ofSeconds(5)), "ожидание должно прерваться сразу");
        brain.interrupt();
    }
}
