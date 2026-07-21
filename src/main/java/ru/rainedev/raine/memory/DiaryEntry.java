package ru.rainedev.raine.memory;

/**
 * Запись дневника — единица долгой памяти.
 *
 * @param id         он же имя файла: unix-время создания
 * @param body       текст записи, как его читает модель
 * @param embedding  вектор для поиска по смыслу
 * @param metadata   опыт использования записи
 */
public record DiaryEntry(String id, String body, double[] embedding, Metadata metadata) {

    /**
     * @param score      накопленная полезность: растёт, когда запись оказывается к месту
     * @param confidence 1 означает факт, а не домысел — такие записи не переписываются
     * @param lastUsed   когда последний раз попадала в контекст
     * @param usageCount сколько раз пригодилась
     */
    public record Metadata(double score, double confidence, String lastUsed, int usageCount) {

        public static Metadata fresh() {
            return new Metadata(0, 0, "never", 0);
        }

        /** Запись пригодилась: чем выше близость, тем заметнее прибавка. */
        public Metadata used(double relatedness, String now) {
            return new Metadata(score + (relatedness - 0.5) * 2, confidence, now, usageCount + 1);
        }
    }

    public DiaryEntry withMetadata(Metadata updated) {
        return new DiaryEntry(id, body, embedding, updated);
    }
}
