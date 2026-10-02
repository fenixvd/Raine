package ru.rainedev.raine.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

/**
 * Пересматривает память во сне: близкие записи сводятся вместе, уточняются
 * и переписываются заново — так же, как человеческая память за ночь.
 * <p>
 * Обычно берётся самое свежее, но иногда всплывает что-то давнее и случайное.
 * Это не причуда: именно так во сне и вспоминается позорный случай трёхлетней
 * давности, а заодно старые записи изредка пересматриваются вместе с новыми.
 */
public final class SleepConsolidation {

    private static final Logger log = LoggerFactory.getLogger(SleepConsolidation.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Насколько часто берётся свежая запись, а не случайная. */
    private static final double RECENT_CHANCE = 0.8;

    /** Короче — обрывок, а не мысль. */
    private static final int MIN_ENTRY_LENGTH = 10;

    private static final int MAX_ATTEMPTS = 3;

    /** Записи с такой уверенностью — установленные факты, их не переписывают. */
    private static final double FACT = 0.9999;

    /** Такой уверенностью модель помечает то, что стоит забыть. */
    private static final double FORGET = -0.99;

    private final Diary diary;
    private final LlmClient llm;
    private final java.util.function.Supplier<String> prompt;
    private final Path archive;
    private final int maxBodyLength;
    private final RandomGenerator random;
    private final int maxRequests;
    private final long maxTokens;
    private int requests;
    private long tokens;
    private Instant deadline;
    private BooleanSupplier interrupted;

    private boolean allowed() {
        return requests < maxRequests && tokens < maxTokens
                && Instant.now().isBefore(deadline) && !interrupted.getAsBoolean()
                && !Thread.currentThread().isInterrupted();
    }

    public SleepConsolidation(Diary diary, LlmClient llm, String prompt, Path archive,
                              int maxBodyLength, RandomGenerator random) {
        this(diary, llm, () -> prompt, archive, maxBodyLength, random);
    }

    public SleepConsolidation(Diary diary, LlmClient llm, java.util.function.Supplier<String> prompt, Path archive,
                              int maxBodyLength, RandomGenerator random) {
        this(diary, llm, prompt, archive, maxBodyLength, random, 20, 100_000);
    }

    public SleepConsolidation(Diary diary, LlmClient llm, java.util.function.Supplier<String> prompt, Path archive,
                              int maxBodyLength, RandomGenerator random, int maxRequests, long maxTokens) {
        if (maxRequests < 1 || maxTokens < 1) throw new IllegalArgumentException("Limits must be positive");
        this.maxRequests = maxRequests;
        this.maxTokens = maxTokens;
        this.diary = diary;
        this.llm = llm;
        this.prompt = prompt;
        this.archive = archive;
        this.maxBodyLength = maxBodyLength;
        this.random = random;
    }

    /**
     * @param budget   сколько времени можно потратить
     * @param wokeUp   если вернёт true, работа прекращается: её позвали
     */
    public void run(Duration budget, BooleanSupplier wokeUp) {
        if (budget.isZero() || budget.isNegative() || wokeUp.getAsBoolean()) return;
        requests = 0;
        tokens = 0;
        deadline = Instant.now().plus(budget);
        interrupted = wokeUp;
        diary.reload();
        if (diary.isEmpty()) {
            return;
        }

        // работаем со снимком: записи выбывают по мере пересмотра
        List<DiaryEntry> pending = new ArrayList<>(diary.query(new double[0]).stream().map(Diary.Match::entry)
                .filter(entry -> entry.metadata().confidence() < FACT).toList());
        pending.sort((a, b) -> b.id().compareTo(a.id()));

        int reviewed = 0;
        while (!pending.isEmpty() && allowed()) {
            DiaryEntry target = take(pending);
            List<DiaryEntry> group = gather(target, pending);

            log.info("Свожу {} записей ({} знаков)", group.size(),
                    group.stream().mapToInt(entry -> entry.body().length()).sum());
            String rewritten = ask(group);
            if (rewritten.isEmpty()) {
                continue;
            }
            StoreResult stored = store(rewritten);
            if (!stored.complete()) {
                log.warn("Пересмотр не сохранён полностью — исходные записи остаются активными");
                break;
            }
            int saved = stored.saved();
            retire(group);
            reviewed += group.size();
            log.info("Во сне пересмотрено {} записей, на их месте {}", group.size(), saved);
        }

        diary.reload();
        if (reviewed > 0) {
            log.info("Сон закончен, пересмотрено записей: {}", reviewed);
        }
    }

    /** Чаще всего — самое свежее, изредка — что-то случайное из давнего. */
    private DiaryEntry take(List<DiaryEntry> pending) {
        int index = random.nextDouble() < RECENT_CHANCE ? 0 : random.nextInt(pending.size());
        return pending.remove(index);
    }

    /** Собирает вокруг записи то, что с ней связано: вместе их и пересматривают. */
    private List<DiaryEntry> gather(DiaryEntry target, List<DiaryEntry> pending) {
        List<DiaryEntry> group = new ArrayList<>();
        group.add(target);

        int length = target.body().length();
        for (Diary.Match match : diary.query(target.embedding())) {
            if (length > maxBodyLength && group.size() >= 3) {
                break;
            }
            DiaryEntry related = match.entry();
            if (related.id().equals(target.id()) || related.metadata().confidence() >= FACT || !pending.remove(related)) {
                continue;
            }
            group.add(related);
            length += related.body().length();
        }
        return group;
    }

    private String ask(List<DiaryEntry> group) {
        StringBuilder body = new StringBuilder();
        for (DiaryEntry entry : group) {
            if (!body.isEmpty()) {
                body.append("\n\n---\n\n");
            }
            body.append("{\"confidence\":").append(entry.metadata().confidence()).append("}\n")
                    .append(entry.body()).append('\n');
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if (!allowed()) return "";
                requests++;
                var response = llm.chat(prompt.get(), List.of(Message.user(body.toString())), null);
                tokens += response.totalTokens();
                String answer = response.text();
                if (answer != null && !answer.isBlank() && !ModelText.looksLikeToolCall(answer)) {
                    return answer;
                }
            } catch (RuntimeException e) {
                log.debug("Пересмотр не удался, попытка {}: {}", attempt, e.getMessage());
            }
        }
        return "";
    }

    private record Replacement(String text, double confidence, double[] embedding) {}
    private record StoreResult(int saved, boolean complete) {}

    private StoreResult store(String answer) {
        List<Replacement> replacements = new ArrayList<>();
        boolean explicitForget = false;
        // Validate the entire answer and compute every embedding before writing anything.
        try {
            for (String chunk : answer.split("\n---")) {
                String text = chunk.strip();
                if (text.isEmpty()) continue;
                double confidence = 0;
                if (text.startsWith("{")) {
                    int end = text.indexOf('}');
                    if (end < 0) return new StoreResult(0, false);
                    JsonNode meta = MAPPER.readTree(text.substring(0, end + 1));
                    if (!meta.path("confidence").isNumber()) return new StoreResult(0, false);
                    confidence = meta.path("confidence").asDouble();
                    if (!Double.isFinite(confidence)) return new StoreResult(0, false);
                    text = text.substring(end + 1).strip();
                }
                if (confidence < FORGET) {
                    explicitForget = true;
                    continue;
                }
                if (text.length() < MIN_ENTRY_LENGTH || !allowed()) return new StoreResult(0, false);
                requests++;
                double[] vector = llm.embedding(text);
                if (vector == null || vector.length == 0) return new StoreResult(0, false);
                for (double value : vector) if (!Double.isFinite(value)) return new StoreResult(0, false);
                replacements.add(new Replacement(text, Math.clamp(confidence, -0.99, 0.99), vector));
            }
            if (replacements.isEmpty() && !explicitForget) return new StoreResult(0, false);
            if (!Instant.now().isBefore(deadline) || interrupted.getAsBoolean()
                    || Thread.currentThread().isInterrupted()) return new StoreResult(0, false);
            List<DiaryEntry> created = new ArrayList<>();
            try {
                for (Replacement replacement : replacements) {
                    created.add(diary.save(replacement.text(), replacement.embedding(), replacement.confidence()));
                }
            } catch (RuntimeException failure) {
                for (DiaryEntry entry : created) Files.deleteIfExists(diary.directory().resolve(entry.id() + ".md"));
                diary.reload();
                throw failure;
            }
            return new StoreResult(created.size(), true);
        } catch (IOException | RuntimeException failure) {
            log.warn("Замена памяти не сохранена: {}", failure.getMessage());
            return new StoreResult(0, false);
        }
    }

    /**
     * Пересмотренные записи убираются в архив, а не удаляются. Алгоритм
     * переписывает память необратимо, и возможность вернуться назад дороже
     * места на диске.
     */
    private void retire(List<DiaryEntry> group) {
        for (DiaryEntry entry : group) {
            if (entry.metadata().confidence() >= FACT) {
                continue;   // установленный факт переписыванию не подлежит
            }
            try {
                Files.createDirectories(archive);
                Path file = diary.directory().resolve(entry.id() + ".md");
                if (Files.exists(file)) {
                    Files.move(file, archive.resolve(entry.id() + ".md"), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.debug("Не удалось убрать запись {} в архив: {}", entry.id(), e.getMessage());
            }
        }
    }
}
