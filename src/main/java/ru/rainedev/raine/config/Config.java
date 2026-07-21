package ru.rainedev.raine.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Настройки бота. Читаются из config.properties рядом с проектом,
 * любое значение перекрывается переменной окружения (RAINE_API_ID и т.д.).
 */
public record Config(
        int apiId,
        String apiHash,
        String phone,
        long ownerId,
        Path sessionDir,
        Path promptsDir,
        Path diaryDir,
        Path workingMemoryFile,
        int diaryInjectionMaxLength,
        double diaryPlagiarismThreshold,
        Vision vision,
        Voice voice,
        Camera camera,
        String webSearchUrl,
        String webSearchKey,
        Llm llm,
        String embeddingModel,
        Character character,
        long contextTokenLimit,
        String lockdown,
        boolean sleepConsolidation,
        Night night,
        Around around,
        int noticeDelaySeconds,
        boolean readOnly
) {

    /** Параметры OpenAI-совместимого эндпоинта. */
    public record Llm(String baseUrl, String model, String apiKey, double temperature) {}

    /**
     * Модели зрения. Дешёвая для потокового — стикеры и аватарки, основная
     * для того, что важно разглядеть.
     */
    public record Vision(boolean enabled, String model, String cheapModel, Path cacheDir) {}

    /**
     * Ночной сон. Время выбирается заново каждую ночь в пределах окна:
     * подъём ровно в 7:00 каждый день — это будильник, а не живой человек.
     */
    public record Night(boolean enabled, java.time.LocalTime bedtimeFrom, java.time.LocalTime bedtimeTo,
                        java.time.LocalTime wakeFrom, java.time.LocalTime wakeTo) {}

    /** Знание об окружающем: время суток и погода. */
    public record Around(boolean enabled, double latitude, double longitude) {}

    /** Синтез речи для голосовых сообщений. */
    public record Voice(boolean enabled, String baseUrl, String apiKey, String model, String voice,
                        double speed, int sampleRate, int bitrate, Path directory) {}

    /** Генерация снимков. */
    public record Camera(boolean enabled, String baseUrl, String apiKey, java.util.List<String> models,
                         Path gallery, int maxTrials) {}

    /** Значения, подставляемые в промпты вместо ${CHARACTER_NAME} и прочих. */
    public record Character(String name, String nickname, String papikName) {}

    private static final Path DEFAULT_FILE = Path.of("config.properties");

    public static Config load() {
        return load(DEFAULT_FILE);
    }

    public static Config load(Path file) {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (var in = Files.newBufferedReader(file)) {
                props.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException("Не удалось прочитать " + file, e);
            }
        }

        return new Config(
                Integer.parseInt(require(props, "api_id")),
                require(props, "api_hash"),
                require(props, "phone"),
                Long.parseLong(require(props, "owner_id")),
                Path.of(get(props, "session_dir", "session")),
                Path.of(get(props, "prompts_dir", "prompts")),
                Path.of(get(props, "diary_dir", "data/diary")),
                Path.of(get(props, "working_memory_file", "data/working_memory.md")),
                Integer.parseInt(get(props, "diary_injection_max_length", "4000")),
                Double.parseDouble(get(props, "diary_plagiarism_threshold", "0.97")),
                new Vision(
                        Boolean.parseBoolean(get(props, "vision_enabled", "true")),
                        get(props, "vision_model", "openai/gpt-4o-mini"),
                        get(props, "vision_model_cheap", "qwen/qwen3.6-flash"),
                        Path.of(get(props, "vision_cache_dir", "cache/images"))),
                new Voice(
                        Boolean.parseBoolean(get(props, "voice_enabled", "true")),
                        get(props, "voice_base_url", require(props, "llm_base_url")),
                        get(props, "voice_api_key", require(props, "llm_api_key")),
                        get(props, "voice_model", "google/gemini-3.1-flash-tts-preview"),
                        get(props, "voice_name", "Leda"),
                        Double.parseDouble(get(props, "voice_speed", "1.2")),
                        Integer.parseInt(get(props, "voice_sample_rate", "24000")),
                        Integer.parseInt(get(props, "voice_bitrate", "24000")),
                        Path.of(get(props, "voice_dir", "data/voice_messages"))),
                new Camera(
                        Boolean.parseBoolean(get(props, "camera_enabled", "true")),
                        get(props, "camera_base_url", "https://aihorde.net/api/v2/"),
                        get(props, "camera_api_key", ""),
                        java.util.List.of(get(props, "camera_models",
                                "WAI-NSFW-illustrious-SDXL,AMPonyXL,AlbedoBase XL (SDXL),Nova Anime XL").split(",")),
                        Path.of(get(props, "camera_gallery", "data/gallery")),
                        Integer.parseInt(get(props, "camera_max_trials", "3"))),
                get(props, "web_search_url", "https://ollama.com/api/web_search"),
                get(props, "web_search_key", ""),
                new Llm(
                        require(props, "llm_base_url"),
                        require(props, "llm_model"),
                        require(props, "llm_api_key"),
                        Double.parseDouble(get(props, "llm_temperature", "0.2"))),
                get(props, "embedding_model", "openai/text-embedding-3-large"),
                new Character(
                        get(props, "character_name", "Raine"),
                        get(props, "character_nickname", "@raine_tyan"),
                        get(props, "papik_name", "RaineDev")),
                Long.parseLong(get(props, "context_token_limit", "40000")),
                get(props, "lockdown", "owner_only"),
                Boolean.parseBoolean(get(props, "sleep_consolidation", "false")),
                new Night(
                        Boolean.parseBoolean(get(props, "night_sleep", "true")),
                        java.time.LocalTime.parse(get(props, "night_bedtime_from", "23:00")),
                        java.time.LocalTime.parse(get(props, "night_bedtime_to", "01:00")),
                        java.time.LocalTime.parse(get(props, "night_wake_from", "06:00")),
                        java.time.LocalTime.parse(get(props, "night_wake_to", "09:00"))),
                new Around(
                        Boolean.parseBoolean(get(props, "around_enabled", "true")),
                        Double.parseDouble(get(props, "around_latitude", "55.75")),
                        Double.parseDouble(get(props, "around_longitude", "37.62"))),
                Integer.parseInt(get(props, "notice_delay_seconds", "30")),
                Boolean.parseBoolean(get(props, "read_only", "true")));
    }

    private static String get(Properties props, String key, String fallback) {
        String env = System.getenv("RAINE_" + key.toUpperCase());
        if (env != null && !env.isBlank()) {
            return env;
        }
        return props.getProperty(key, fallback);
    }

    private static String require(Properties props, String key) {
        String value = get(props, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Не задан параметр '" + key + "'. Добавь его в config.properties "
                            + "или задай переменную RAINE_" + key.toUpperCase());
        }
        return value;
    }
}
