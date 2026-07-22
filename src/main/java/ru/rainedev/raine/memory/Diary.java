package ru.rainedev.raine.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Долгая память: записи в markdown с вектором в заголовке.
 * <p>
 * Формат совместим с прежним хранилищем, поэтому накопленный дневник
 * читается как есть, без переноса и пересчёта.
 */
public final class Diary {

    private static final Logger log = LoggerFactory.getLogger(Diary.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEPARATOR = "---";

    /** Насколько уверенность записи влияет на её место в выдаче. */
    private static final double CONFIDENCE_FACTOR = 0.01;

    private final Path dir;
    private final Map<String, DiaryEntry> entries = new LinkedHashMap<>();

    /** Считает вектор для записи, у которой его не оказалось. */
    @FunctionalInterface
    public interface Embedder {
        double[] embed(String text);
    }

    private Embedder embedder;

    public Diary(Path dir) {
        this.dir = dir;
        reload();
    }

    public void embedder(Embedder embedder) {
        this.embedder = embedder;
    }

    public Path directory() {
        return dir;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Перечитывает каталог. Битые файлы пропускаются, а не роняют запуск. */
    public void reload() {
        entries.clear();
        if (!Files.isDirectory(dir)) {
            log.info("Каталог дневника {} пуст — память начинается с нуля", dir);
            return;
        }
        long startedAt = System.currentTimeMillis();
        try (Stream<Path> files = Files.list(dir)) {
            // полтысячи файлов по три тысячи чисел в каждом: по одному это
            // ощутимая пауза на запуске, а разбираются они независимо
            // порядок в дневнике сохраняется, а разбор идёт вперемешку: каждая
            // запись читается сама по себе и других не ждёт
            files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList()
                    .parallelStream()
                    .map(Diary::readQuietly)
                    .flatMap(java.util.Optional::stream)
                    .sorted(java.util.Comparator.comparing(DiaryEntry::id))
                    .forEachOrdered(entry -> entries.put(entry.id(), entry));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать дневник " + dir, e);
        }
        log.info("Дневник загружен: {} записей за {} мс", entries.size(), System.currentTimeMillis() - startedAt);
    }

    private static java.util.Optional<DiaryEntry> readQuietly(Path file) {
        try {
            return java.util.Optional.of(parse(file));
        } catch (RuntimeException | IOException e) {
            log.warn("Пропускаю повреждённую запись {}: {}", file.getFileName(), e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private static DiaryEntry parse(Path file) throws IOException {
        String raw = Files.readString(file);
        String[] lines = raw.split("\n", 4);
        if (lines.length < 4 || !SEPARATOR.equals(lines[0].strip()) || !SEPARATOR.equals(lines[2].strip())) {
            throw new IllegalArgumentException("не найден заголовок с метаданными");
        }
        JsonNode meta = MAPPER.readTree(lines[1]);
        JsonNode vector = meta.path("embedding");
        double[] embedding = new double[vector.size()];
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = vector.get(i).asDouble();
        }
        String id = file.getFileName().toString().replaceFirst("\\.md$", "");
        return new DiaryEntry(id, lines[3].strip(), embedding, new DiaryEntry.Metadata(
                meta.path("score").asDouble(0),
                meta.path("confidence").asDouble(0),
                meta.path("lastUsed").asText("never"),
                meta.path("usageCount").asInt(0)));
    }

    /** Найденная запись и то, насколько она близка запросу. */
    public record Match(DiaryEntry entry, double relatedness) {}

    /** Все записи по убыванию близости. Порог применяет вызывающий — он у него плавающий. */
    /**
     * @param vector пустой — значит спрашивают не «что похоже», а «покажи всё»:
     *               так перебирают дневник для порыва и для пересмотра во сне.
     *               Досчитывать векторы в этом случае незачем — сравнивать
     *               всё равно не с чем, а обращений к сети выходит по числу
     *               записей
     */
    public List<Match> query(double[] vector) {
        List<Match> matches = new ArrayList<>(entries.size());
        for (DiaryEntry entry : List.copyOf(entries.values())) {
            DiaryEntry ready = vector.length == 0 ? entry : withEmbedding(entry, vector.length);
            // уверенность слегка поднимает запись в выдаче: проверенному верят охотнее
            double relatedness = Similarity.cosine(vector, ready.embedding())
                    + ready.metadata().confidence() * CONFIDENCE_FACTOR;
            matches.add(new Match(ready, relatedness));
        }
        matches.sort(Comparator.comparingDouble(Match::relatedness).reversed());
        return matches;
    }

    /**
     * Запись без вектора искать невозможно — она была бы невидима навсегда.
     * Поэтому вектор досчитывается при первой встрече и сохраняется.
     */
    private DiaryEntry withEmbedding(DiaryEntry entry, int expectedLength) {
        if (entry.embedding().length == expectedLength || embedder == null || entry.body().isBlank()) {
            return entry;
        }
        try {
            // табуляции ломали разбор ответа эндпоинта
            double[] embedding = embedder.embed(entry.body().replace("\t", "  "));
            DiaryEntry updated = new DiaryEntry(entry.id(), entry.body(), embedding, entry.metadata());
            entries.put(updated.id(), updated);
            write(updated);
            log.info("Досчитан вектор для записи {}", updated.id());
            return updated;
        } catch (RuntimeException e) {
            log.debug("Не удалось досчитать вектор для {}: {}", entry.id(), e.getMessage());
            return entry;
        }
    }

    /**
     * Забирает запись в контекст: начисляет опыт, сохраняет на диск и убирает
     * из выдачи. Пока контекст не сброшен, та же запись повторно не всплывёт —
     * иначе она жевалась бы по кругу, занимая место.
     */
    public DiaryEntry take(Match match) {
        DiaryEntry updated = use(match);
        entries.remove(updated.id());
        return updated;
    }

    /**
     * Начисляет опыт и сохраняет, но оставляет запись в выдаче.
     * Так поступает поиск по запросу: там от повторов защищаются в пределах
     * одного запроса, а насовсем прятать запись незачем.
     */
    public DiaryEntry use(Match match) {
        DiaryEntry updated = match.entry()
                .withMetadata(match.entry().metadata().used(match.relatedness(), Instant.now().toString()));
        entries.put(updated.id(), updated);
        write(updated);
        return updated;
    }

    /** Новая запись. Идентификатор — время создания, как и у прежних. */
    public DiaryEntry save(String body, double[] embedding) {
        return save(body, embedding, 0);
    }

    /**
     * @param confidence насколько записи доверять: -1 ложь, 0 предположение,
     *                   1 установленный факт. Пересматривается во сне
     */
    public DiaryEntry save(String body, double[] embedding, double confidence) {
        long id = Instant.now().getEpochSecond();
        while (Files.exists(dir.resolve(id + ".md"))) {
            id++;
        }
        DiaryEntry entry = new DiaryEntry(String.valueOf(id), body.strip(), embedding,
                new DiaryEntry.Metadata(0, confidence, "never", 0));
        write(entry);
        entries.put(entry.id(), entry);
        return entry;
    }

    private void write(DiaryEntry entry) {
        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("score", entry.metadata().score());
        meta.put("confidence", entry.metadata().confidence());
        meta.put("lastUsed", entry.metadata().lastUsed());
        meta.put("usageCount", entry.metadata().usageCount());
        ArrayNode vector = meta.putArray("embedding");
        for (double value : entry.embedding()) {
            vector.add(Math.round(value * 1_000_000d) / 1_000_000d);
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(entry.id() + ".md"),
                    SEPARATOR + "\n" + MAPPER.writeValueAsString(meta) + "\n" + SEPARATOR + "\n\n"
                            + entry.body() + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить запись " + entry.id(), e);
        }
    }
}
