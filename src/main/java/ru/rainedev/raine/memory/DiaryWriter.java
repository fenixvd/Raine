package ru.rainedev.raine.memory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

/**
 * Переносит разговор в долгую память: просит модель пересказать его записями
 * и сохраняет то, чего в дневнике ещё нет.
 */
public final class DiaryWriter {

    private static final Logger log = LoggerFactory.getLogger(DiaryWriter.class);

    /** Разделитель между записями в ответе модели. */
    private static final String SEPARATOR = "---";

    /** Короче этого — обрывок, а не воспоминание. */
    private static final int MIN_ENTRY_LENGTH = 20;

    private static final int MAX_ATTEMPTS = 3;

    private final Diary diary;
    private final LlmClient llm;
    private final java.util.function.Supplier<String> savePrompt;
    private final double plagiarismThreshold;

    public DiaryWriter(Diary diary, LlmClient llm, String savePrompt, double plagiarismThreshold) {
        this(diary, llm, () -> savePrompt, plagiarismThreshold);
    }

    public DiaryWriter(Diary diary, LlmClient llm, java.util.function.Supplier<String> savePrompt,
                       double plagiarismThreshold) {
        this.diary = diary;
        this.llm = llm;
        this.savePrompt = savePrompt;
        this.plagiarismThreshold = plagiarismThreshold;
    }

    /** @return сохранённые записи; пусто, если сохранять оказалось нечего */
    public List<DiaryEntry> save(String systemPrompt, List<Message> context) {
        if (context.isEmpty()) {
            return List.of();
        }

        String summary = askForSummary(systemPrompt, context);
        if (summary.isBlank()) {
            log.warn("Пересказ разговора не получен — записи не сохранены");
            return List.of();
        }
        return persist(summary);
    }

    private String askForSummary(String systemPrompt, List<Message> context) {
        List<Message> conversation = new ArrayList<>(context);
        conversation.add(Message.user(savePrompt.get()));

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ChatResponse response = llm.chat(systemPrompt, conversation, null);
            String content = response.text() == null ? "" : response.text().strip();

            if (content.isBlank()) {
                // модель попыталась действовать инструментом, хотя их здесь нет
                conversation.add(Message.user("Tools are not available; write the summary without tools."));
                continue;
            }
            if (ModelText.looksLikeToolCall(content)) {
                log.warn("Модель написала вызов инструмента текстом, попытка {} из {}", attempt, MAX_ATTEMPTS);
                conversation.add(Message.user(
                        "Do not write tool calls. Answer with plain text only: the diary entries themselves."));
                continue;
            }
            return content;
        }
        return "";
    }

    private List<DiaryEntry> persist(String summary) {
        // модель порой ставит разделитель с лишними пробелами
        String normalized = summary.replace("- --", SEPARATOR).replace("-- -", SEPARATOR);

        List<DiaryEntry> saved = new ArrayList<>();
        for (String part : Arrays.stream(normalized.split(SEPARATOR)).map(String::strip).toList()) {
            if (part.length() < MIN_ENTRY_LENGTH) {
                continue;
            }
            double[] embedding;
            try {
                embedding = llm.embedding(part);
            } catch (RuntimeException e) {
                log.warn("Не удалось посчитать вектор записи, пропускаю: {}", e.getMessage());
                continue;
            }
            if (isPlagiarism(embedding, part)) {
                continue;
            }
            saved.add(diary.save(part, embedding));
        }
        log.info("В дневник добавлено записей: {}", saved.size());
        return saved;
    }

    /** То же самое, но другими словами, память не обогащает — только раздувает. */
    private boolean isPlagiarism(double[] embedding, String part) {
        List<Diary.Match> matches = diary.query(embedding);
        if (matches.isEmpty()) {
            return false;
        }
        Diary.Match closest = matches.getFirst();
        if (closest.relatedness() > plagiarismThreshold) {
            log.info("Уже записано (близость {}), пропускаю: {}",
                    String.format("%.3f", closest.relatedness()), part.lines().findFirst().orElse("").strip());
            return true;
        }
        return false;
    }
}
