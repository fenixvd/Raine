package ru.rainedev.raine.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.prompt.Prompts;

class VisionTest {

    private static final class ScriptedLlm implements LlmClient {
        private final Deque<String> answers = new ArrayDeque<>();
        String lastModel = "";
        String lastContext = "";
        int looks;

        ScriptedLlm willSee(String description) {
            answers.addLast(description);
            return this;
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {1};
        }

        @Override
        public String describeImage(String model, String systemPrompt, String context, byte[] image) {
            looks++;
            lastModel = model;
            lastContext = context;
            return answers.isEmpty() ? "" : answers.removeFirst();
        }
    }

    private Vision visionWith(ScriptedLlm llm, Path cache) {
        return new Vision(llm, new Prompts(Path.of("prompts"),
                new Config.Character("Raine", "@raine_tyan", "RaineDev")),
                cache, "основная-модель", "дешёвая-модель", "Raine");
    }

    private static Path imageFile(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, new byte[] {1, 2, 3});
        return file;
    }

    @Test
    void describesPhotoAndMarksItUp(@TempDir Path dir) throws IOException {
        ScriptedLlm llm = new ScriptedLlm().willSee("Человек с котом на руках");

        String result = visionWith(llm, dir.resolve("cache")).describe(
                imageFile(dir, "photo.jpg"), Vision.Kind.PHOTO, List.of());

        assertTrue(result.contains("Человек с котом"), result);
        assertTrue(result.contains("<photo description>"), result);
    }

    @Test
    void stickersGoToTheCheaperModel(@TempDir Path dir) throws IOException {
        // стикеров в переписке много, а качество их описания роли не играет
        ScriptedLlm llm = new ScriptedLlm().willSee("Кот машет лапой");

        visionWith(llm, dir.resolve("cache")).describe(
                imageFile(dir, "sticker.webp"), Vision.Kind.STICKER, List.of());

        assertEquals("дешёвая-модель", llm.lastModel);
    }

    @Test
    void photosGoToTheMainModel(@TempDir Path dir) throws IOException {
        ScriptedLlm llm = new ScriptedLlm().willSee("описание");

        visionWith(llm, dir.resolve("cache")).describe(
                imageFile(dir, "photo.jpg"), Vision.Kind.PHOTO, List.of());

        assertEquals("основная-модель", llm.lastModel);
    }

    @Test
    void looksAtTheSameImageOnlyOnce(@TempDir Path dir) throws IOException {
        ScriptedLlm llm = new ScriptedLlm().willSee("Человек с котом");
        Path cache = dir.resolve("cache");
        Path photo = imageFile(dir, "photo.jpg");
        Vision vision = visionWith(llm, cache);

        String first = vision.describe(photo, Vision.Kind.PHOTO, List.of());
        String second = vision.describe(photo, Vision.Kind.PHOTO, List.of());

        assertEquals(first, second);
        assertEquals(1, llm.looks, "повторный взгляд на ту же картинку стоит денег и времени");
    }

    @Test
    void knowsHerOwnAppearanceAndTheConversation(@TempDir Path dir) throws IOException {
        ScriptedLlm llm = new ScriptedLlm().willSee("описание");

        visionWith(llm, dir.resolve("cache")).describe(imageFile(dir, "photo.jpg"), Vision.Kind.PHOTO,
                List.of(Message.user("посмотри, что я нашёл")));

        assertTrue(llm.lastContext.contains("<character name=\"Raine\">"), "должна узнавать себя на фото");
        assertTrue(llm.lastContext.contains("посмотри, что я нашёл"), "описание должно быть к месту");
    }

    @Test
    void givesUpAfterRepeatedEmptyAnswers(@TempDir Path dir) throws IOException {
        // в оригинале здесь безусловный повтор — модель может молчать вечно
        ScriptedLlm llm = new ScriptedLlm();

        String result = visionWith(llm, dir.resolve("cache")).describe(
                imageFile(dir, "photo.jpg"), Vision.Kind.PHOTO, List.of());

        assertEquals("", result);
        assertTrue(llm.looks <= 3, "попыток должно быть немного, было: " + llm.looks);
    }

    @Test
    void retriesOnceWhenModelStumbles(@TempDir Path dir) throws IOException {
        ScriptedLlm llm = new ScriptedLlm().willSee("").willSee("Человек с котом");

        String result = visionWith(llm, dir.resolve("cache")).describe(
                imageFile(dir, "photo.jpg"), Vision.Kind.PHOTO, List.of());

        assertTrue(result.contains("Человек с котом"), result);
        assertEquals(2, llm.looks);
    }

    @Test
    void missingFileDoesNotBreakTheConversation(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm().willSee("описание");

        String result = visionWith(llm, dir.resolve("cache"))
                .describe(dir.resolve("нет-такого.jpg"), Vision.Kind.PHOTO, List.of());

        assertEquals("", result);
        assertEquals(0, llm.looks);
    }

    @Test
    void withoutVisionOnlyAttachmentTypeIsVisible() {
        assertFalse(ru.rainedev.raine.phone.MediaDescriber.NONE.describe(null).contains("description"));
    }
}
