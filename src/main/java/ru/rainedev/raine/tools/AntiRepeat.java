package ru.rainedev.raine.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.LowQualityException;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.memory.Similarity;

/**
 * Ловит повторы: самое заметное, что выдаёт бота — одно и то же разными словами.
 * <p>
 * Сравнивает новое сообщение с недавними собственными. Слишком похоже на одно
 * из них — значит разговор исчерпан, и живой человек не стал бы дописывать.
 * Похоже на все сразу в среднем — значит она ходит по кругу, меняя обёртку.
 */
public final class AntiRepeat {

    private static final Logger log = LoggerFactory.getLogger(AntiRepeat.class);


    /**
     * После каждого срабатывания порог временно поднимается. Иначе модель,
     * которой нечего добавить, попадёт в бесконечный отказ: перефразирует,
     * снова получает отказ, и так до предела шагов.
     */
    private static final double INDULGENCE_STEP = 0.07;

    private final LlmClient llm;
    private final java.util.function.Supplier<String> rejectionPrompt;
    private final ru.rainedev.raine.config.Config.Repeat limits;
    private final Map<String, double[]> spoken = new LinkedHashMap<>();

    private double indulgence;

    public AntiRepeat(LlmClient llm, String rejectionPrompt) {
        this(llm, () -> rejectionPrompt);
    }

    public AntiRepeat(LlmClient llm, java.util.function.Supplier<String> rejectionPrompt) {
        this(llm, rejectionPrompt, new ru.rainedev.raine.config.Config.Repeat(0.95, 0.85, 32));
    }

    public AntiRepeat(LlmClient llm, java.util.function.Supplier<String> rejectionPrompt,
                      ru.rainedev.raine.config.Config.Repeat limits) {
        this.llm = llm;
        this.rejectionPrompt = rejectionPrompt;
        this.limits = limits;
    }

    /** Сообщения из истории чата — чтобы не повторяться и после перезапуска. */
    public void remember(String text) {
        if (text == null || text.isBlank() || spoken.containsKey(text)) {
            return;
        }
        try {
            spoken.put(text, llm.embedding(text));
        } catch (RuntimeException e) {
            log.debug("Не удалось запомнить сообщение для проверки на повтор: {}", e.getMessage());
        }
        forgetOldest();
    }

    /**
     * @throws LowQualityException если сказанное слишком похоже на уже сказанное
     */
    public void check(String text) {
        if (spoken.isEmpty()) {
            remember(text);
            return;
        }

        double[] target;
        try {
            target = llm.embedding(text);
        } catch (RuntimeException e) {
            // проверка не обязательна: без неё разговор просто менее строгий
            log.debug("Проверка на повтор пропущена: {}", e.getMessage());
            return;
        }

        double maxSimilarity = 0;
        double sum = 0;
        for (double[] previous : spoken.values()) {
            double similarity = Similarity.cosine(target, previous);
            maxSimilarity = Math.max(maxSimilarity, similarity);
            sum += similarity;
        }
        double avgSimilarity = sum / spoken.size();

        if (maxSimilarity > limits.triggerMax() + indulgence) {
            reject("повтор сказанного ранее, схожесть %.2f".formatted(maxSimilarity));
        }
        if (avgSimilarity > limits.triggerAvg() + indulgence) {
            reject("хождение по кругу, средняя схожесть %.2f".formatted(avgSimilarity));
        }

        indulgence = 0;
        spoken.put(text, target);
        forgetOldest();
    }

    private void reject(String reason) {
        indulgence += INDULGENCE_STEP;
        log.info("Отклонено как повтор: {}", reason);
        throw new LowQualityException(rejectionPrompt.get());
    }

    private void forgetOldest() {
        while (spoken.size() > limits.maxHistory()) {
            spoken.remove(spoken.keySet().iterator().next());
        }
    }
}
