package ru.rainedev.raine.vision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.prompt.Prompts;

/**
 * Превращает картинку в описание — иначе на присланное фото Raine видит
 * только пометку о том, что фото было.
 * <p>
 * Описания кэшируются рядом: одна и та же картинка попадается в переписке
 * много раз, а смотреть на неё заново стоит денег и времени.
 */
public final class Vision {

    private static final Logger log = LoggerFactory.getLogger(Vision.class);

    /** Больше — почти всегда зря: модель начинает пересказывать саму себя. */
    private static final int MAX_ATTEMPTS = 3;

    /** Что смотрим. От этого зависит и промпт, и какой моделью. */
    public enum Kind {
        /** Присланное фото — важно разглядеть, берём основную модель. */
        PHOTO("photo", "photo_to_text.md", false),
        /** Стикер — достаточно понять смысл, хватит дешёвой. */
        STICKER("sticker", "sticker_to_text.md", true),
        /** Аватарка — понять, с кем говоришь. */
        AVATAR("chat_photo", "photo_to_text.md", true),
        /** Один кадр видео: подпись короткая, кадров много. */
        VIDEO("video", "video_frame_to_text.md", true);

        private final String tag;
        private final String prompt;
        private final boolean cheap;

        Kind(String tag, String prompt, boolean cheap) {
            this.tag = tag;
            this.prompt = prompt;
            this.cheap = cheap;
        }
    }

    private final LlmClient llm;
    private final Prompts prompts;
    private final Path cacheDir;
    private final String mainModel;
    private final String cheapModel;
    private final String characterName;

    /** Слух подключается отдельно: без него видео остаётся немым. */
    private ru.rainedev.raine.speech.SpeechToText hearing;

    public Vision(LlmClient llm, Prompts prompts, Path cacheDir,
                  String mainModel, String cheapModel, String characterName) {
        this.llm = llm;
        this.prompts = prompts;
        this.cacheDir = cacheDir;
        this.mainModel = mainModel;
        this.cheapModel = cheapModel;
        this.characterName = characterName;
    }

    public void hearing(ru.rainedev.raine.speech.SpeechToText hearing) {
        this.hearing = hearing;
    }

    /**
     * Описывает уже готовые байты картинки. Кэша здесь нет: у таких байтов
     * нет файла, по которому их можно было бы узнать в следующий раз.
     */
    public String describe(byte[] image, Kind kind, List<Message> context) {
        try {
            String description = llm.describeImage(kind.cheap ? cheapModel : mainModel,
                    prompts.load(kind.prompt), buildContext(context), Downscale.toFit(image));
            return description == null || description.isBlank() ? "" : wrap(kind, description.strip());
        } catch (RuntimeException e) {
            log.warn("Не удалось разглядеть кадры: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Видео описывается покадрово, и каждая подпись помечена своим временем.
     * Склеить кадры в одну картинку было бы дешевле, но тогда пропадает
     * главное: что было раньше, а что позже. «Сначала показывает кота, потом
     * роняет чашку» — это уже рассказ, а не набор картинок.
     *
     * @return размеченная лента подписей или пустая строка
     */
    public String describeVideo(List<VideoFrames.Frame> frames, List<Message> context) {
        return describeVideo(frames, context, null);
    }

    /**
     * @param file сам файл — из него слышно звук. Без него видео немое
     */
    public String describeVideo(List<VideoFrames.Frame> frames, List<Message> context, java.nio.file.Path file) {
        if (frames.isEmpty()) {
            // картинку разобрать не вышло, но в звуке может быть всё главное
            return heard(file)
                    .map(speech -> "<video transcription>\n<f track=\"audio\">\n" + speech
                            + "\n</f>\n</video transcription>\n")
                    .orElse("");
        }
        String prompt = prompts.load(Kind.VIDEO.prompt);
        String situation = buildContext(context);
        StringBuilder timeline = new StringBuilder();
        for (VideoFrames.Frame frame : frames) {
            String caption = captionOf(frame, prompt, situation);
            if (caption.isEmpty()) {
                continue;
            }
            timeline.append("<f from=\"").append(VideoFrames.timestamp(frame.from()))
                    .append("\" to=\"").append(VideoFrames.timestamp(frame.to())).append("\">\n")
                    .append(caption).append("\n</f>\n");
        }
        if (timeline.isEmpty()) {
            return "";
        }
        heard(file).ifPresent(speech ->
                timeline.append("<f track=\"audio\">\n").append(speech).append("\n</f>\n"));
        log.info("Просмотрено кадров: {}", frames.size());
        return "<video transcription>\n" + timeline
                + "</video transcription instructions=\"You finished watching this video and should "
                + "acknowledge its contents shown above.\">\n";
    }

    /**
     * В ролике половина смысла обычно в звуке: без него «человек что-то говорит
     * в камеру» — это всё, что она узнает.
     */
    private java.util.Optional<String> heard(java.nio.file.Path file) {
        if (file == null || hearing == null || !hearing.isAvailable()) {
            return java.util.Optional.empty();
        }
        java.util.Optional<java.nio.file.Path> audio = VideoAudio.extract(file);
        if (audio.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            java.util.Optional<String> speech = hearing.listen(audio.get());
            speech.ifPresent(text -> log.info("Услышано в видео: {}", text));
            return speech;
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(audio.get());
            } catch (java.io.IOException e) {
                log.debug("Временная дорожка не удалилась: {}", e.getMessage());
            }
        }
    }

    private String captionOf(VideoFrames.Frame frame, String prompt, String situation) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String caption = llm.describeImage(cheapModel, prompt, situation, Downscale.toFit(frame.jpeg()));
                if (caption != null && !caption.isBlank()) {
                    return caption.strip();
                }
                // пустой ответ обычно значит, что модель захлебнулась рассуждениями
                log.debug("Пустая подпись к кадру на {} с, попытка {}", frame.from(), attempt);
            } catch (RuntimeException e) {
                log.warn("Кадр на {} с не разглядеть: {}", frame.from(), e.getMessage());
                return "";
            }
        }
        return "";
    }

    /**
     * @param context последние сообщения — без них модель описывает картинку
     *                вообще, а не применительно к разговору
     * @return размеченное описание или пустая строка, если посмотреть не вышло
     */
    public String describe(Path file, Kind kind, List<Message> context) {
        String cached = fromCache(file);
        if (cached != null) {
            return wrap(kind, cached);
        }

        byte[] image;
        try {
            image = Files.readAllBytes(file);
        } catch (IOException e) {
            log.warn("Не удалось прочитать {}: {}", file.getFileName(), e.getMessage());
            return "";
        }

        String model = kind.cheap ? cheapModel : mainModel;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String description = llm.describeImage(model, prompts.load(kind.prompt), buildContext(context),
                        Downscale.toFit(image));
                if (description != null && !description.isBlank()) {
                    toCache(file, description.strip());
                    log.info("Разглядела {}: {}", kind.tag, description.strip().lines().findFirst().orElse(""));
                    return wrap(kind, description.strip());
                }
                // пустой ответ обычно значит, что модель захлебнулась рассуждениями
                log.debug("Пустое описание, попытка {} из {}", attempt, MAX_ATTEMPTS);
            } catch (RuntimeException e) {
                log.warn("Не удалось разглядеть {}: {}", file.getFileName(), e.getMessage());
                return "";
            }
        }
        return "";
    }

    /**
     * Модель смотрит не в пустоту: ей даётся собственная внешность Raine, чтобы
     * она узнавала её на фото, и текущий разговор — чтобы описание было к месту.
     */
    private String buildContext(List<Message> context) {
        StringBuilder out = new StringBuilder("<context>\n<character name=\"")
                .append(characterName).append("\">\n")
                .append(prompts.load("character_appearance.md"))
                .append("\n</character>\n");
        if (context != null) {
            for (Message message : context) {
                if (message.content() != null && !message.content().isBlank()) {
                    out.append("<context_item>\n").append(message.content()).append("\n</context_item>\n");
                }
            }
        }
        return out.append("</context>\n\nDescribe the photo.").toString();
    }

    private static String wrap(Kind kind, String description) {
        return "<%s description>\n%s\n</%s>\n".formatted(kind.tag, description, kind.tag);
    }

    private String fromCache(Path file) {
        try {
            Path cached = cacheFor(file);
            return Files.exists(cached) ? Files.readString(cached) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void toCache(Path file, String description) {
        try {
            Files.createDirectories(cacheDir);
            Files.writeString(cacheFor(file), description);
        } catch (IOException e) {
            log.debug("Описание не сохранилось в кэш: {}", e.getMessage());
        }
    }

    private Path cacheFor(Path file) {
        return cacheDir.resolve(file.getFileName() + ".md");
    }
}
