package ru.rainedev.raine.memory;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

/**
 * Достаёт из дневника то, что относится к текущему разговору.
 * <p>
 * Порог близости плавающий. Пустой дневник и дневник на тысячу записей требуют
 * разной строгости, а какой она должна быть — заранее неизвестно. Поэтому порог
 * подстраивается сам: ничего не нашлось — опускается, набралось с избытком —
 * поднимается до близости последней влезшей записи.
 */
public final class DiaryMemory implements Memory {

    private static final Logger log = LoggerFactory.getLogger(DiaryMemory.class);

    private static final String TAG = "your_diary_page additional_context just_for_reasoning no_plagiarism no_copy";

    /** Сколько последних сообщений описывают «о чём сейчас речь». */
    private static final int CONTEXT_DEPTH = 3;

    private final Diary diary;
    private final LlmClient llm;
    private final int maxLength;

    /**
     * Ниже этой близости запись не вспоминается, как бы ни просел плавающий
     * порог. Без нижней границы пустой или разрозненный дневник утягивает
     * планку в ноль, и в разговор начинает лезть что попало.
     */
    private final double floor;

    private double threshold = 0.5;

    public DiaryMemory(Diary diary, LlmClient llm, int maxLength) {
        this(diary, llm, maxLength, 0.0);
    }

    public DiaryMemory(Diary diary, LlmClient llm, int maxLength, double floor) {
        this.diary = diary;
        this.llm = llm;
        this.maxLength = maxLength;
        this.floor = floor;
    }

    public double threshold() {
        return threshold;
    }

    @Override
    public String recall(List<Message> recentContext) {
        if (diary.isEmpty() || maxLength <= 0 || recentContext.isEmpty()) {
            return "";
        }

        double[] vector;
        try {
            vector = llm.embedding(topicOf(recentContext));
        } catch (RuntimeException e) {
            // без воспоминаний разговор беднее, но продолжается
            log.warn("Не удалось посчитать вектор запроса: {}", e.getMessage());
            return "";
        }

        StringBuilder recalled = new StringBuilder();
        for (Diary.Match match : diary.query(vector)) {
            if (match.relatedness() < Math.max(floor, threshold)) {
                if (recalled.isEmpty()) {
                    // ничего не проходит — планка завышена, опускаем её к тому, что реально есть
                    threshold = Math.max(floor, 0.05 + 0.9 * match.relatedness());
                    log.debug("Ничего не вспомнилось, порог снижен до {}", threshold);
                }
                break;
            }
            if (recalled.length() >= maxLength) {
                // набралось с избытком — впредь спрашиваем строже
                threshold = Math.max(floor, match.relatedness());
                break;
            }
            if (alreadyInContext(recentContext, match.entry().body())) {
                continue;
            }
            DiaryEntry entry = diary.take(match);
            recalled.append("<").append(TAG).append(">\n")
                    .append(entry.body()).append("\n")
                    .append("</").append(TAG).append(">\n");
            log.info("Вспомнилось (близость {}): {}", String.format("%.3f", match.relatedness()),
                    entry.body().lines().findFirst().orElse("").strip());
        }
        return recalled.toString();
    }

    /**
     * Запрос к памяти строится и по рассуждениям тоже, а не только по сказанному:
     * то, о чём она сейчас думает, точнее задаёт, что стоит вспомнить.
     */
    private static String topicOf(List<Message> context) {
        StringBuilder topic = new StringBuilder();
        for (Message message : context.subList(Math.max(0, context.size() - CONTEXT_DEPTH), context.size())) {
            append(topic, message.reasoningContent());
            append(topic, message.content());
        }
        return topic.toString();
    }

    private static void append(StringBuilder topic, String text) {
        if (text != null && !text.isBlank()) {
            topic.append(text).append("\n\n---\n\n");
        }
    }

    /** Тратить токены на то, что уже лежит в контексте, незачем. */
    private static boolean alreadyInContext(List<Message> context, String body) {
        return context.stream().anyMatch(m -> m.content() != null && m.content().contains(body));
    }
}
