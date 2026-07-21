package ru.rainedev.raine.cli;

import java.util.List;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.OpenAiCompatibleClient;
import ru.rainedev.raine.memory.Diary;
import ru.rainedev.raine.memory.DiaryEntry;

/**
 * Проверка памяти без запуска бота: задаём запрос — видим, что вспомнится
 * и насколько близко.
 * <p>
 * Иначе убедиться, что дневник ищет как надо, можно только вживую, в разговоре,
 * где вмешивается ещё десяток вещей. Здесь видно ровно одно: запрос и ответ
 * памяти с числами.
 */
public final class RecallCommand {

    /** Столько записей показываем: ниже уже видно, что дальше только хуже. */
    private static final int SHOWN = 10;

    private RecallCommand() {}

    public static void run(Config config, String query) {
        if (query.isBlank()) {
            System.out.println("Укажи, о чём спросить память: recall \"поездка на Байкал\"");
            return;
        }

        Diary diary = new Diary(config.diaryDir());
        LlmClient llm = new OpenAiCompatibleClient(config.llm(), config.embeddingModel());
        diary.embedder(llm::embedding);

        System.out.printf("Дневник: %s, записей %d%n", config.diaryDir(), diary.size());
        System.out.printf("Запрос: %s%n%n", query);

        List<Diary.Match> matches = diary.query(llm.embedding(query));
        if (matches.isEmpty()) {
            System.out.println("Дневник пуст — вспоминать нечего.");
            return;
        }

        for (Diary.Match match : matches.subList(0, Math.min(SHOWN, matches.size()))) {
            DiaryEntry entry = match.entry();
            System.out.printf("%.3f  %s  (%s, к месту приходилась %d раз)%n",
                    match.relatedness(),
                    entry.id(),
                    firstLine(entry.body()),
                    entry.metadata().usageCount());
        }
        System.out.printf("%n  порог, ниже которого запись не вспомнится: %.2f%n", config.diaryMinRelatedness());
    }

    private static String firstLine(String body) {
        String line = body.lines().findFirst().orElse("").strip();
        return line.length() > 80 ? line.substring(0, 77) + "..." : line;
    }
}
