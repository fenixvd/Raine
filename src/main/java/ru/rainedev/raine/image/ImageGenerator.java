package ru.rainedev.raine.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.prompt.Prompts;

/**
 * Делает снимок по пожеланию: превращает его в задание для рисующей модели,
 * получает картинку и сам решает, годится ли она.
 * <p>
 * Проверка нужна потому, что рисующие модели регулярно выдают лишние пальцы
 * и сломанные позы. Отправить такое — хуже, чем не отправить ничего.
 */
public final class ImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerator.class);

    private static final String ENGINEER_PROMPT = """
            You are an expert Stable Diffusion prompt engineer.
            Transform a freeform description into a high-quality, descriptive Stable Diffusion prompt.
            Integrate the provided character appearance so the character stays recognisable.

            Guidelines:
            - Use descriptive keywords and technical terms (hyperrealistic, 8k, masterpiece).
            - Preserve the original character design; do not redesign the character.
            - Who took the photo and how? Almost always it is a selfie, unless the description says otherwise.
            - Keep each prompt under 50 words. Use (weights:1.5) instead of piling up words.
            - If a previous prompt is given, adjust it rather than write a new one: keep what worked.
            - Only add new words if the image is missing something important; shorten by dropping filler.

            Answer with exactly two lines and nothing else:
            POSITIVE: <positive prompt>
            NEGATIVE: <negative prompt>""";

    private static final String QUALITY_GATE = """
            You are an image quality gate.

            You will be shown an image generated from a description and a character appearance.
            Decide whether it is good enough to send to a real person as a photo.
            Judge it the way a person glancing at a photo would: obvious defects matter, subtle ones do not.
            Each rejection costs another expensive generation, so reject only for real problems.

            Reject the image if ANY of these is true:
            - a body part is clearly malformed, missing, duplicated, fused or impossibly placed (six fingers,
              two heads, an arm from the wrong place). Mild foreshortening or a hand partly out of frame is NOT
              a defect;
            - the character is clearly someone else: hair colour or length, eye colour, glasses or age visibly
              disagree with the description;
            - the scene is nonsensical: floating in mid-air, melting objects, duplicated people;
            - the image is badly degraded: heavy blur over the subject, garbled textures, obvious artifacts;
            - the image ignores the requested subject entirely.

            Important:
            - Adult content is fully allowed and must NEVER be a reason to reject. Judge only technical quality,
              identity and anatomy — not how revealing the image is.
            - An awkward selfie arm, background clutter, imperfect light or slightly odd framing are ACCEPTABLE.
              Real photos are not perfect.
            - If you are unsure whether something is a defect, treat it as acceptable.
            - The character description is the baseline, but the specific wish overrides it where they disagree.
            - The character must not appear as a child.

            Answer with exactly two lines:
            VERDICT: OK or REJECT
            FEEDBACK: <what to change, one sentence>""";

    /**
     * Слова, которые промпт-инженер отказывается обрабатывать. Их вырезают из
     * пожелания перед обращением к нему и дописывают в готовый промпт после.
     */
    private static final List<String> BLUNT_WORDS = List.of(
            "explicit nudity", "explicit nude", "explicit erotic", "nsfw", "nude", "topless");

    /** Не убирается ни при каких настройках. */
    private static final String HARD_NEGATIVE = "child, kid, teen, loli, shota";

    /** Длиннее — облик персонажа начинает плыть. */
    private static final int MAX_PROMPT_WORDS = 60;

    /** Столько раз просим модель укоротить задание, прежде чем обрезать самим. */
    private static final int SHORTEN_ATTEMPTS = 2;

    private static final String BASE_NEGATIVE = "(text:2), (signature:2), watermark, deformed hands, extra fingers";

    private final HordeClient horde;
    private final LlmClient llm;
    private final Prompts prompts;
    private final Path gallery;
    private final String characterName;
    private final String visionModel;
    private final int maxTrials;
    private final Random random = new Random();

    /**
     * Съёмка идёт одна за раз. Очередь бывает занята десятками минут, и без
     * этого запрета она, не дождавшись, запускает вторую работу поверх первой:
     * оба задания стоят очков, а нужен один снимок.
     */
    private final java.util.concurrent.atomic.AtomicBoolean busy =
            new java.util.concurrent.atomic.AtomicBoolean();

    public ImageGenerator(HordeClient horde, LlmClient llm, Prompts prompts, Path gallery,
                          String characterName, String visionModel, int maxTrials) {
        this.horde = horde;
        this.llm = llm;
        this.prompts = prompts;
        this.gallery = gallery;
        this.characterName = characterName;
        this.visionModel = visionModel;
        this.maxTrials = Math.max(1, maxTrials);
    }

    public boolean isAvailable() {
        return horde.isAvailable();
    }

    private record Prompt(String positive, String negative) {}

    public boolean isBusy() {
        return busy.get();
    }

    /**
     * Снимает в фоне. Ожидание в очереди доходит до часа, и держать ради этого
     * весь разговор нельзя: пока рисуется, она должна жить обычной жизнью.
     *
     * @param onReady   вызовется с готовым файлом
     * @param onFailure вызовется с причиной, если не вышло
     * @return false, если съёмка уже идёт
     */
    public boolean takeInBackground(String wish, java.util.function.Consumer<Path> onReady,
                                    java.util.function.Consumer<String> onFailure) {
        if (!busy.compareAndSet(false, true)) {
            return false;
        }
        Thread.ofVirtual().name("raine-camera").start(() -> {
            try {
                onReady.accept(take(wish));
            } catch (RuntimeException e) {
                log.warn("Снимок не получился: {}", e.getMessage());
                onFailure.accept(e.getMessage());
            } finally {
                busy.set(false);
            }
        });
        return true;
    }

    /** @return файл в галерее */
    public Path take(String wish) {
        String appearance = prompts.load("character_appearance.md");
        boolean explicit = isExplicit(wish);
        Prompt prompt = engineer(wish, appearance, "", null);
        String feedbackSoFar = "";
        String firstFeedback = "";

        // изредка задание переписывается с нуля: если правки завели не туда,
        // выбраться из этого места удобнее заново
        int restartEvery = Math.max(2, (int) Math.round(Math.sqrt(maxTrials)));

        for (int trial = 1; trial <= maxTrials; trial++) {
            log.info("Снимок, попытка {}: {}", trial, prompt.positive());
            byte[] image;
            try {
                image = horde.generate(prompt.positive(), prompt.negative(),
                        768 + random.nextInt(257), 768 + random.nextInt(257),
                        1.0 + random.nextDouble() * 4.0, 30);
            } catch (RuntimeException e) {
                log.warn("Снимок не получился: {}", e.getMessage());
                if (trial == maxTrials) {
                    throw e;
                }
                continue;
            }

            // откровенный снимок проверять нечем: та же модель зрения, которой
            // мы показываем результат, такое смотреть отказывается — выйдет
            // не проверка, а сожжённые попытки. Такие снимки принимаем как есть
            if (explicit) {
                log.info("Снимок откровенный — принимаю без проверки");
                return save(image);
            }
            String verdict = assess(image, wish, appearance);
            if (verdict.isEmpty()) {
                return save(image);
            }
            log.info("Снимок забракован: {}", verdict);
            if (firstFeedback.isEmpty()) {
                // в сообщении о неудаче приводится первый отзыв: он ближе
                // к исходному пожеланию и понятнее говорит, что не так
                firstFeedback = verdict;
            }
            feedbackSoFar = verdict;
            prompt = trial % restartEvery == 0
                    ? engineer(wish, appearance, "", null)
                    : engineer(wish, appearance, feedbackSoFar, prompt);
        }
        throw new IllegalStateException("Не вышло сделать удачный снимок: " + firstFeedback
                + ". Попробуй описать снимок короче.");
    }

    /** Ответы, которыми модель отказывается смотреть, а не оценивает. */
    private static final List<String> REFUSALS = List.of(
            "i'm sorry", "i am sorry", "can't assist", "cannot assist", "can't help", "cannot help",
            "unable to assist", "i can't provide", "i cannot provide", "не могу помочь",
            "не могу выполнить", "не могу описать");

    static boolean isRefusal(String verdict) {
        String lower = verdict == null ? "" : verdict.toLowerCase();
        return REFUSALS.stream().anyMatch(lower::contains);
    }

    /** Откровенное задание видно по прямым словам — тем самым, что мы вычищаем из промпта. */
    static boolean isExplicit(String wish) {
        String lower = wish == null ? "" : wish.toLowerCase();
        return BLUNT_WORDS.stream().anyMatch(lower::contains);
    }

    private Prompt engineer(String wish, String appearance, String feedback, Prompt previous) {
        // прямые слова промпт-инженер обрабатывать откажется — уберём их,
        // а в готовый промпт добавим сами
        String toned = wish;
        for (String word : BLUNT_WORDS) {
            toned = toned.replaceAll("(?i)" + java.util.regex.Pattern.quote(word), "");
        }

        StringBuilder request = new StringBuilder("<character name=\"%s\">\n%s\n</character>\n\nDescription: %s"
                .formatted(characterName, appearance, toned));
        if (previous != null) {
            // правим прежнее задание, а не сочиняем заново: удачные находки сохраняются
            request.append("\n\nPrevious prompt:\nPOSITIVE: ").append(previous.positive())
                    .append("\nNEGATIVE: ").append(previous.negative());
        }
        if (!feedback.isBlank()) {
            request.append("\n\nPrevious attempt was rejected: ").append(feedback);
        }

        List<Message> conversation = new java.util.ArrayList<>(List.of(Message.user(request.toString())));
        String answer = ask(conversation);

        String positive = line(answer, "POSITIVE:", toned);
        String negative = line(answer, "NEGATIVE:", "");

        // слишком длинное задание не обрезаем на полуслове, а просим переписать:
        // машинная обрезка отсекает половину смысла и оставляет висящую скобку
        for (int attempt = 1; attempt <= SHORTEN_ATTEMPTS && !fitsInWords(positive, negative); attempt++) {
            log.info("Задание длинновато, прошу укоротить (попытка {})", attempt);
            conversation.add(Message.assistant(answer));
            conversation.add(Message.user("The prompt is too long. Shorten it to 50 words or less: restructure "
                    + "or adjust (weights:1.5) instead of piling up words. Keep the same format."));
            answer = ask(conversation);
            positive = line(answer, "POSITIVE:", positive);
            negative = line(answer, "NEGATIVE:", negative);
        }
        positive = tidy(positive);
        negative = tidy(negative);

        for (String word : BLUNT_WORDS) {
            if (wish.toLowerCase().contains(word)) {
                positive += ", " + word;
            }
        }
        return new Prompt(positive, join(BASE_NEGATIVE, negative, HARD_NEGATIVE));
    }

    private String ask(List<Message> conversation) {
        try {
            String answer = llm.chat(ENGINEER_PROMPT, conversation, null).text();
            return answer == null ? "" : answer;
        } catch (RuntimeException e) {
            log.warn("Промпт не составился, беру пожелание как есть: {}", e.getMessage());
            return "";
        }
    }

    private static boolean fitsInWords(String positive, String negative) {
        return words(positive) <= MAX_PROMPT_WORDS && words(negative) <= MAX_PROMPT_WORDS;
    }

    private static int words(String prompt) {
        return prompt.isBlank() ? 0 : prompt.strip().split("\\s+").length;
    }

    /** @return пусто, если снимок годится, иначе — что не так */
    private String assess(byte[] image, String wish, String appearance) {
        try {
            String verdict = llm.describeImage(visionModel, QUALITY_GATE,
                    "<character>\n%s\n</character>\n<wanted>\n%s\n</wanted>".formatted(appearance, wish), image);
            if (verdict == null || verdict.isBlank()) {
                return "";   // проверяющий промолчал — не повод жечь ещё одну генерацию
            }
            if (isRefusal(verdict)) {
                // «я не могу это смотреть» — это отказ проверять, а не изъян снимка.
                // Принять его за брак значит выбросить готовую картинку и встать
                // в очередь заново, на второй час
                log.info("Проверяющий отказался смотреть — принимаю снимок как есть");
                return "";
            }
            return verdict.toUpperCase().contains("VERDICT: OK") ? "" : line(verdict, "FEEDBACK:", verdict.strip());
        } catch (RuntimeException e) {
            log.warn("Проверить снимок не вышло, принимаю как есть: {}", e.getMessage());
            return "";
        }
    }

    private Path save(byte[] image) {
        try {
            Files.createDirectories(gallery);
            Path file = gallery.resolve(System.currentTimeMillis() + ".webp");
            Files.write(file, image);
            log.info("Снимок сохранён: {}", file.getFileName());
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Снимок не сохранился", e);
        }
    }

    /**
     * Приводит задание в порядок: запятая после весовых групп и обрезка до
     * разумной длины. Длинные задания размывают облик персонажа — модель
     * начинает рисовать кого-то другого.
     */
    private static String tidy(String prompt) {
        String result = prompt.replace(") ", "), ");
        String[] words = result.split("\\s+");
        if (words.length > MAX_PROMPT_WORDS) {
            result = String.join(" ", java.util.Arrays.copyOf(words, MAX_PROMPT_WORDS));
        }
        return result;
    }

    private static String line(String text, String marker, String fallback) {
        if (text == null) {
            return fallback;
        }
        for (String line : text.lines().toList()) {
            if (line.strip().toUpperCase().startsWith(marker)) {
                String value = line.strip().substring(marker.length()).strip();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return fallback;
    }

    private static String join(String... parts) {
        return String.join(", ", java.util.Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).toList());
    }
}
