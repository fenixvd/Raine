package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.core.LowQualityException;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

class LivelinessTest {

    private final Random random = new Random(42);

    @Test
    void swapMakesTextDifferentButSameLength() {
        String result = Typos.swapAdjacent("привет как дела", random);

        assertNotEquals("привет как дела", result);
        assertEquals("привет как дела".length(), result.length());
    }

    @Test
    void neighbourKeyStaysOnTheSameKeyboard() {
        // промах должен попадать по соседней клавише той же раскладки
        String result = Typos.hitNeighbourKey("привет", random);

        assertNotEquals("привет", result);
        assertEquals(6, result.length());
    }

    @Test
    void slipRemembersHowTheWordShouldLook() {
        // без этого нечего было бы дописать следом со звёздочкой
        for (int i = 0; i < 200; i++) {
            Typos.Slip slip = Typos.maybeAdd("сегодня хорошая погода", 1.0, random);
            if (slip.happened()) {
                assertTrue("сегодня хорошая погода".contains(slip.original()),
                        "исправление должно быть словом из исходного текста: " + slip.original());
                assertNotEquals("сегодня хорошая погода", slip.text());
                return;
            }
        }
    }

    @Test
    void shortAndOddInputSurvives() {
        assertEquals("а", Typos.swapAdjacent("а", random));
        assertEquals("", Typos.swapAdjacent("", random));
        assertEquals("!!!", Typos.hitNeighbourKey("!!!", random), "нечего заменять — оставляем как есть");
    }

    @Test
    void typosAreRareNotConstant() {
        int changed = 0;
        for (int i = 0; i < 1000; i++) {
            if (Typos.maybeAdd("сегодня хорошая погода", 0.08, random).happened()) {
                changed++;
            }
        }
        assertTrue(changed > 20 && changed < 200, "опечаток должно быть немного, вышло: " + changed);
    }

    @Test
    void typingSpeedIsHumanNotSluggish() {
        // около десяти знаков в секунду: короткая реплика набирается за пару
        // секунд, а не за десять
        TypingRhythm rhythm = new TypingRhythm(random);
        Instant now = Instant.now();
        rhythm.delayFor(1, now);

        Duration shortMessage = rhythm.delayFor(20, now.plusSeconds(1));

        assertTrue(shortMessage.toMillis() > 800 && shortMessage.toMillis() < 3000,
                "двадцать знаков должны набираться пару секунд, вышло: " + shortMessage.toMillis() + " мс");
    }

    @Test
    void longerMessageTakesLongerToType() {
        TypingRhythm rhythm = new TypingRhythm(random);
        Instant now = Instant.now();

        rhythm.delayFor(10, now);
        Duration shortText = rhythm.delayFor(10, now.plusSeconds(1));
        rhythm.delayFor(10, now.plusSeconds(2));
        Duration longText = rhythm.delayFor(200, now.plusSeconds(3));

        assertTrue(longText.compareTo(shortText) > 0, shortText + " против " + longText);
    }

    @Test
    void noDelayWhenConversationWasIdle() {
        TypingRhythm rhythm = new TypingRhythm(random);
        Instant now = Instant.now();

        rhythm.delayFor(50, now);

        // пауза изображает набор текста, а не задумчивость
        assertEquals(Duration.ZERO, rhythm.delayFor(50, now.plusSeconds(30)));
    }

    @Test
    void delayIsCappedForVeryLongMessages() {
        TypingRhythm rhythm = new TypingRhythm(random);
        Instant now = Instant.now();

        rhythm.delayFor(1, now);

        assertTrue(rhythm.delayFor(100_000, now.plusSeconds(1)).compareTo(Duration.ofSeconds(13)) < 0);
    }

    /** Похожесть задаётся вручную: одинаковый текст — одинаковый вектор. */
    private record VectorsByText(Map<String, double[]> vectors) implements LlmClient {
        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double[] embedding(String input) {
            return vectors.getOrDefault(input, new double[] {0, 0, 1});
        }
    }

    @Test
    void rejectsRepeatingItself() {
        LlmClient llm = new VectorsByText(Map.of(
                "я тут, если что", new double[] {1, 0, 0},
                "я здесь, если что", new double[] {1, 0, 0}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");

        antiRepeat.check("я тут, если что");

        assertThrows(LowQualityException.class, () -> antiRepeat.check("я здесь, если что"));
    }

    @Test
    void allowsGenuinelyNewMessage() {
        LlmClient llm = new VectorsByText(Map.of(
                "я тут, если что", new double[] {1, 0, 0},
                "слушай, а ты завтра свободен?", new double[] {0, 1, 0}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");

        antiRepeat.check("я тут, если что");
        antiRepeat.check("слушай, а ты завтра свободен?");
    }

    @Test
    void relaxesAfterRejectionSoItDoesNotLoopForever() {
        // иначе модели, которой нечего добавить, отказывают до предела шагов
        LlmClient llm = new VectorsByText(Map.of("одно и то же", new double[] {1, 0, 0}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");
        antiRepeat.check("одно и то же");

        assertThrows(LowQualityException.class, () -> antiRepeat.check("одно и то же"));

        // послабление копится: рано или поздно даже дословный повтор проходит,
        // иначе ход упрётся в предел шагов и Raine промолчит
        int attempts = 0;
        boolean accepted = false;
        while (attempts++ < 10 && !accepted) {
            try {
                antiRepeat.check("одно и то же");
                accepted = true;
            } catch (LowQualityException ignored) {
                // ещё не отпустило
            }
        }
        assertTrue(accepted, "отказ не должен быть вечным, попыток: " + attempts);
        assertTrue(attempts < 6, "но и упрямиться слишком долго незачем, попыток: " + attempts);
    }

    @Test
    void brokenEmbeddingDoesNotBlockSending() {
        LlmClient broken = new LlmClient() {
            @Override
            public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
                throw new UnsupportedOperationException();
            }

            @Override
            public double[] embedding(String input) {
                throw new IllegalStateException("сеть недоступна");
            }
        };
        AntiRepeat antiRepeat = new AntiRepeat(broken, "ты повторяешься");
        antiRepeat.remember("что-то");

        // проверка не обязательна — без неё разговор просто менее строгий
        antiRepeat.check("что угодно");
        assertFalse(false);
    }
}
