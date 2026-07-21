package ru.rainedev.raine.memory;

/** Близость по смыслу между двумя векторами. */
public final class Similarity {

    private Similarity() {}

    /**
     * Близость от 0 до 1, где 1 — совпадение.
     * <p>
     * Косинус сам по себе лежит от -1 до 1, и его приводят к привычному виду:
     * все пороги в системе — доля, а не косинус. На сырой шкале «0.97» означало
     * бы почти недостижимое совпадение, и проверка на повторы не срабатывала бы.
     */
    public static double cosine(double[] a, double[] b) {
        return (raw(a, b) + 1.0) / 2.0;
    }

    private static double raw(double[] a, double[] b) {
        if (a.length != b.length || a.length == 0) {
            return -1;   // после приведения это даст ноль
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
