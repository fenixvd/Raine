package ru.rainedev.raine.config;

import java.nio.file.Path;

/**
 * Настройки бота. Читаются из config.properties рядом с проектом,
 * любое значение перекрывается переменной окружения (RAINE_API_ID и т.д.).
 * <p>
 * Каждая настройка объявляется вместе с пояснением: недостающие дописываются
 * в файл при запуске, так что после обновления видно, что появилось нового.
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
        double diaryMinRelatedness,
        int diaryConsolidationBudget,
        Vision vision,
        Voice voice,
        Hearing hearing,
        Camera camera,
        String webSearchUrl,
        String webSearchKey,
        Llm llm,
        String embeddingModel,
        Character character,
        long contextTokenLimit,
        String lockdown,
        boolean lockdownAllowChannels,
        boolean sleepConsolidation,
        Night night,
        Around around,
        int noticeDelaySeconds,
        boolean readOnly,
        boolean llmTranscript,
        Behaviour behaviour,
        Repeat repeat
) {

    /** Параметры OpenAI-совместимого эндпоинта. */
    public record Llm(String baseUrl, String model, String apiKey, double temperature) {}

    /**
     * Модели зрения. Дешёвая для потокового — стикеры и аватарки, основная
     * для того, что важно разглядеть.
     */
    /**
     * @param recentDepth у скольких последних сообщений разглядывать вложения:
     *                    разбор десятка картинок стоит минуты молчания, а разговор
     *                    идёт про последние из них
     */
    public record Vision(boolean enabled, String model, String cheapModel, Path cacheDir, int recentDepth) {}

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
                        String language, double speed, int sampleRate, int bitrate, Path directory) {}

    /**
     * Слух: расшифровка звуковой дорожки видео. Голосовые и кружки
     * расшифровывает сам Telegram за премиум, а обычное видео — нет.
     */
    public record Hearing(boolean enabled, String baseUrl, String apiKey, String model, String language) {}

    /** Генерация снимков. */
    public record Camera(boolean enabled, String baseUrl, String apiKey, java.util.List<String> models,
                         Path gallery, int maxTrials) {}

    /** Значения, подставляемые в промпты вместо ${CHARACTER_NAME} и прочих. */
    public record Character(String name, String nickname, String papikName) {}

    /**
     * Повадки, которые хочется менять, не пересобирая проект.
     *
     * @param chatHistoryLength история чата ограничивается объёмом текста,
     *                          а не числом сообщений: тридцать постов из канала —
     *                          это перебор контекста, а тридцать «ок» — наоборот мало
     * @param wakeOnPinnedChat будят ли ночью сообщения из закреплённых чатов
     * @param dayNaps          отлучается ли она изредка среди дня
     * @param writeToNewPeople может ли писать первой тем, с кем переписки ещё не было
     * @param stickers         пользуется ли стикерами
     * @param toolReminder     как часто напоминать, что переписка — не только текст
     */
    public record Behaviour(int chatHistoryLength, boolean wakeOnPinnedChat, boolean dayNaps,
                            boolean writeToNewPeople, boolean stickers, double toolReminder) {}

    /**
     * Насколько строго ловить повторы за собой.
     *
     * @param triggerMax  совпадение с одним прошлым сообщением
     * @param triggerAvg  средняя похожесть на всё сказанное — хождение по кругу
     * @param maxHistory  сколько своих реплик держать для сравнения
     */
    public record Repeat(double triggerMax, double triggerAvg, int maxHistory) {}

    private static final Path DEFAULT_FILE = Path.of("config.properties");

    public static Path defaultFile() {
        return DEFAULT_FILE;
    }

    public static Config load() {
        return load(DEFAULT_FILE);
    }

    public static Config load(Path file) {
        Settings s = Settings.read(file);
        Config config = new Config(
                Integer.parseInt(s.require("api_id", "идентификатор приложения с my.telegram.org")),
                s.require("api_hash", "ключ приложения с my.telegram.org"),
                s.require("phone", "номер телефона учётной записи в международном формате"),
                s.number("owner_id", 0, "идентификатор владельца: его сообщения важнее всех прочих"),
                s.path("session_dir", "session", "где хранится сессия Telegram"),
                s.path("prompts_dir", "prompts", "папка с промптами"),
                s.path("diary_dir", "data/diary", "папка дневника — долгой памяти"),
                s.path("working_memory_file", "data/working_memory.md", "файл средней памяти"),
                s.integer("diary_injection_max_length", 4000,
                        "сколько букв воспоминаний подмешивать в разговор за раз"),
                s.fraction("diary_plagiarism_threshold", 0.97,
                        "выше этой близости новая запись дневника считается повтором старой"),
                s.fraction("diary_min_relatedness", 0.80,
                        "ниже этой близости запись не вспоминается, как бы ни просел плавающий порог"),
                s.integer("diary_token_count_trigger", 40000,
                        "какого объёма пласт памяти пересматривается во сне за один заход"),
                new Vision(
                        s.flag("vision_enabled", true, "видит ли она картинки и видео"),
                        s.get("vision_model", "openai/gpt-4o-mini", "модель зрения для важного"),
                        s.get("vision_model_cheap", "qwen/qwen3.6-flash",
                                "дешёвая модель зрения: стикеры, аватарки"),
                        s.path("vision_cache_dir", "cache/images", "кэш описаний картинок"),
                        s.integer("vision_recent_messages", 8,
                                "у скольких последних сообщений разглядывать вложения")),
                new Voice(
                        s.flag("voice_enabled", true, "может ли записывать голосовые"),
                        s.get("voice_base_url", s.require("llm_base_url", "адрес модели"), "адрес синтеза речи"),
                        s.get("voice_api_key", s.require("llm_api_key", "ключ модели"), "ключ синтеза речи"),
                        s.get("voice_model", "google/gemini-3.1-flash-tts-preview", "модель синтеза речи"),
                        s.get("voice_name", "Leda", "голос"),
                        s.get("voice_language", "ru", "язык речи: без него синтез угадывает его по тексту"),
                        s.fraction("voice_speed", 1.2, "скорость речи"),
                        s.integer("voice_sample_rate", 24000, "частота дискретизации синтеза"),
                        s.integer("voice_bitrate", 24000, "битрейт голосового сообщения"),
                        s.path("voice_dir", "data/voice_messages", "куда складывать записанные голосовые")),
                new Hearing(
                        s.flag("hearing_enabled", true, "расшифровывать ли звук из присланных видео"),
                        s.get("hearing_base_url", s.require("llm_base_url", "адрес модели"),
                                "адрес распознавания речи"),
                        s.get("hearing_api_key", s.require("llm_api_key", "ключ модели"),
                                "ключ распознавания речи"),
                        s.get("hearing_model", "openai/whisper-1", "модель распознавания речи"),
                        s.get("hearing_language", "ru", "язык, на котором говорят в видео")),
                new Camera(
                        s.flag("camera_enabled", true, "может ли делать снимки"),
                        s.get("camera_base_url", "https://aihorde.net/api/v2/", "адрес очереди рисования"),
                        s.get("camera_api_key", "", "ключ очереди рисования"),
                        java.util.List.of(s.get("camera_models",
                                "WAI-NSFW-illustrious-SDXL,AMPonyXL,AlbedoBase XL (SDXL),Nova Anime XL",
                                "модели рисования через запятую").split(",")),
                        s.path("camera_gallery", "data/gallery", "галерея снимков"),
                        s.integer("camera_max_trials", 3, "сколько раз переснимать, пока снимок не удастся")),
                s.get("web_search_url", "https://ollama.com/api/web_search", "адрес поиска в сети"),
                s.get("web_search_key", "", "ключ поиска в сети; пустой — поиска не будет"),
                new Llm(
                        s.require("llm_base_url", "адрес модели"),
                        s.require("llm_model", "название модели"),
                        s.require("llm_api_key", "ключ модели"),
                        s.fraction("llm_temperature", 0.2, "температура модели")),
                s.get("embedding_model", "openai/text-embedding-3-large", "модель для векторов памяти"),
                new Character(
                        s.get("character_name", "Raine", "имя"),
                        s.get("character_nickname", "@raine_tyan", "ник в Telegram"),
                        s.get("papik_name", "RaineDev", "как она зовёт владельца")),
                s.number("context_token_limit", 40000, "после какого объёма разговор сбрасывается в дневник"),
                s.get("lockdown", "owner_only", "круг общения: owner_only, contacts, everyone"),
                s.flag("lockdown_allow_channels", true, "читать ли каналы при суженном круге общения"),
                s.flag("sleep_consolidation", false, "пересматривать ли память во сне"),
                new Night(
                        s.flag("night_sleep", true, "спит ли она ночью"),
                        s.time("night_bedtime_from", "23:00", "не раньше этого времени ложится"),
                        s.time("night_bedtime_to", "01:00", "не позже этого времени ложится"),
                        s.time("night_wake_from", "06:00", "не раньше этого времени встаёт"),
                        s.time("night_wake_to", "09:00", "не позже этого времени встаёт")),
                new Around(
                        s.flag("around_enabled", true, "знает ли она время суток и погоду за окном"),
                        s.fraction("around_latitude", 55.75, "широта места, где она «живёт»"),
                        s.fraction("around_longitude", 37.62, "долгота места, где она «живёт»")),
                s.integer("notice_delay_seconds", 30, "до скольких секунд тянуть с ответом"),
                s.flag("read_only", true, "только смотреть и ничего не отправлять"),
                s.flag("llm_transcript", true,
                        "записывать ли каждый запрос к модели и ответ в logs/llm — этим разбирают, "
                                + "почему она ответила именно так"),
                new Behaviour(
                        s.integer("chat_max_history_length", 2000,
                                "сколько букв истории чата показывать при открытии"),
                        s.flag("wake_up_on_pinned_chat", false, "будят ли ночью закреплённые чаты"),
                        s.flag("randomly_go_sleep", true, "отлучается ли она изредка среди дня"),
                        s.flag("can_write_to_a_new_person", false,
                                "может ли писать первой тем, с кем переписки ещё не было"),
                        s.flag("use_stickers", true, "пользуется ли стикерами"),
                        s.fraction("tool_reminder_probability", 0.02,
                                "как часто напоминать, что переписка — не только текст")),
                new Repeat(
                        s.fraction("anti_repeat_trigger_max", 0.95,
                                "выше этой близости к одной прошлой реплике — повтор"),
                        s.fraction("anti_repeat_trigger_avg", 0.85,
                                "выше этой средней близости ко всем — хождение по кругу"),
                        s.integer("anti_repeat_max_history", 32,
                                "сколько своих реплик держать для сравнения")));
        s.sync();
        return config;
    }
}
