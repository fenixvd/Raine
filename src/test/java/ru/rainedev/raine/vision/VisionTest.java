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

    /** Кадры видео смотрятся одновременно, поэтому заглушка тоже должна это переживать. */
    private static final class ScriptedLlm implements LlmClient {
        private final java.util.Queue<String> answers = new java.util.concurrent.ConcurrentLinkedQueue<>();
        volatile String lastModel = "";
        volatile String lastContext = "";
        private final java.util.concurrent.atomic.AtomicInteger seen =
                new java.util.concurrent.atomic.AtomicInteger();

        ScriptedLlm willSee(String description) {
            answers.add(description);
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
            seen.incrementAndGet();
            lastModel = model;
            lastContext = context;
            String answer = answers.poll();
            return answer == null ? "" : answer;
        }
    }

    private Vision visionWith(ScriptedLlm llm, Path cache) {
        return new Vision(llm, new Prompts(Path.of("prompts"),
                new Config.Character("Raine", "@raine_tyan", "RaineDev")),
                cache, "основная-модель", "дешёвая-модель", "Raine");
    }

    private static Path imageFile(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        // размером с настоящий файл: слишком мелкие мы не смотрим, потому что
        // это признак недокачанного вложения
        Files.write(file, new byte[2048]);
        return file;
    }

    @Test
    void videoBecomesATimelineWithTimestamps(@TempDir Path dir) {
        // склеить кадры в одну картинку дешевле, но тогда пропадает главное:
        // что было раньше, а что позже
        ScriptedLlm llm = new ScriptedLlm().willSee("кот сидит").willSee("кот роняет чашку");

        String result = visionWith(llm, dir.resolve("cache")).describeVideo(
                List.of(new VideoFrames.Frame(0, 1, new byte[] {1}),
                        new VideoFrames.Frame(1, 2, new byte[] {2})),
                List.of());

        assertEquals(2, llm.seen.get(), "каждый кадр описывается отдельно");
        assertTrue(result.contains("from=\"0:00\" to=\"0:01\""), result);
        assertTrue(result.contains("from=\"0:01\" to=\"0:02\""), result);
        assertTrue(result.contains("кот роняет чашку"), result);
        assertTrue(result.contains("video transcription"), result);
    }

    @Test
    void videoIsWatchedOnlyOnce(@TempDir Path dir) throws IOException {
        // разбор ролика — это шестнадцать обращений к зрению плюс расшифровка звука;
        // повторять их при каждом открытии чата непозволительно дорого
        ScriptedLlm llm = new ScriptedLlm().willSee("кот сидит").willSee("кот сидит");
        Path video = imageFile(dir, "ролик.mp4");
        Vision vision = visionWith(llm, dir.resolve("cache"));
        List<VideoFrames.Frame> frames = List.of(new VideoFrames.Frame(0, 1, new byte[] {1}));

        String first = vision.describeVideo(frames, List.of(), video);
        String again = vision.describeVideo(frames, List.of(), video);

        assertEquals(first, again);
        assertEquals(1, llm.seen.get(), "второй раз смотреть незачем — описание уже есть");
    }

    @Test
    void videoThatCouldNotBeSeenAtAllIsEmpty(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm();   // на все кадры отвечает пустотой

        String result = visionWith(llm, dir.resolve("cache")).describeVideo(
                List.of(new VideoFrames.Frame(0, 1, new byte[] {1})), List.of());

        assertEquals("", result);
    }

    @Test
    void videoWithoutFramesIsNotAskedAbout(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm().willSee("что-то");

        String result = visionWith(llm, dir.resolve("cache")).describeVideo(List.of(), List.of());

        assertEquals("", result);
        assertEquals(0, llm.seen.get(), "спрашивать не о чем — незачем и платить");
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
        assertEquals(1, llm.seen.get(), "повторный взгляд на ту же картинку стоит денег и времени");
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
        assertTrue(llm.seen.get() <= 3, "попыток должно быть немного, было: " + llm.seen.get());
    }

    @Test
    void retriesOnceWhenModelStumbles(@TempDir Path dir) throws IOException {
        ScriptedLlm llm = new ScriptedLlm().willSee("").willSee("Человек с котом");

        String result = visionWith(llm, dir.resolve("cache")).describe(
                imageFile(dir, "photo.jpg"), Vision.Kind.PHOTO, List.of());

        assertTrue(result.contains("Человек с котом"), result);
        assertEquals(2, llm.seen.get());
    }

    @Test
    void missingFileDoesNotBreakTheConversation(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm().willSee("описание");

        String result = visionWith(llm, dir.resolve("cache"))
                .describe(dir.resolve("нет-такого.jpg"), Vision.Kind.PHOTO, List.of());

        assertEquals("", result);
        assertEquals(0, llm.seen.get());
    }

    @Test
    void withoutVisionOnlyAttachmentTypeIsVisible() {
        assertFalse(ru.rainedev.raine.phone.MediaDescriber.NONE.describe(null).contains("description"));
    }
}
