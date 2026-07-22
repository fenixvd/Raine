package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

class IdleRestTest {

    private static final LlmClient SILENT = new LlmClient() {
        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {1};
        }
    };

    /** Считает, сколько раз её спросили, не пора ли отдохнуть. */
    private static final class CountingRest extends Rest {
        private final AtomicInteger asked = new AtomicInteger();

        CountingRest() {
            super(new Random(1));
        }

        @Override
        public void maybeRest() {
            asked.incrementAndGet();
        }
    }

    @Test
    void inSilenceSheAsksHerselfAgainWhetherItIsTimeToSleep() throws InterruptedException {
        // разбуженная среди ночи иначе осталась бы на ногах до утра — просто
        // потому, что вопрос «не пора ли спать» задавался только при новом сообщении
        NotificationLoop loop = new NotificationLoop(SILENT, () -> "промпт", new Toolbox(), 40_000);
        CountingRest rest = new CountingRest();
        loop.rest(rest);
        loop.idleCheck(Duration.ofMillis(50));

        Thread brain = Thread.ofVirtual().start(loop::run);
        Thread.sleep(400);
        brain.interrupt();

        assertTrue(rest.asked.get() > 2, "в тишине вопрос должен повторяться, а спросили " + rest.asked.get());
    }
}
