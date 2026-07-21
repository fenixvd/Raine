package ru.rainedev.raine.tools;

import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Живая переписка не бывает стерильной. Две правдоподобные ошибки:
 * переставить соседние буквы и промахнуться по соседней клавише.
 */
public final class Typos {

    /**
     * Соседние клавиши строятся из рядов раскладки, а не задаются вручную:
     * промах происходит по соседней клавише слева, справа или на соседнем ряду
     * со сдвигом — как на настоящей клавиатуре.
     */
    private static final Map<Character, String> NEIGHBOURS = buildNeighbours(
            new String[] {"qwertyuiop", "asdfghjkl", "zxcvbnm"},
            new String[] {"йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"});

    private static Map<Character, String> buildNeighbours(String[]... layouts) {
        Map<Character, String> map = new java.util.HashMap<>();
        for (String[] rows : layouts) {
            for (int row = 0; row < rows.length; row++) {
                for (int col = 0; col < rows[row].length(); col++) {
                    StringBuilder neighbours = new StringBuilder();
                    if (col > 0) {
                        neighbours.append(rows[row].charAt(col - 1));
                    }
                    if (col + 1 < rows[row].length()) {
                        neighbours.append(rows[row].charAt(col + 1));
                    }
                    if (row > 0 && col < rows[row - 1].length()) {
                        neighbours.append(rows[row - 1].charAt(col));
                    }
                    if (row + 1 < rows.length && col < rows[row + 1].length()) {
                        neighbours.append(rows[row + 1].charAt(col));
                    }
                    map.put(rows[row].charAt(col), neighbours.toString());
                }
            }
        }
        return Map.copyOf(map);
    }

    private Typos() {}

    /** Меняет местами две соседние буквы. */
    public static String swapAdjacent(String text, RandomGenerator random) {
        int[] points = text.codePoints().toArray();
        if (points.length < 2) {
            return text;
        }
        int at = random.nextInt(points.length - 1);
        int swapped = points[at];
        points[at] = points[at + 1];
        points[at + 1] = swapped;
        return new String(points, 0, points.length);
    }

    /** Промах по соседней клавише с учётом раскладки. */
    public static String hitNeighbourKey(String text, RandomGenerator random) {
        int[] points = text.codePoints().toArray();
        for (int attempt = 0; attempt < points.length; attempt++) {
            int at = random.nextInt(points.length);
            char lower = Character.toLowerCase((char) points[at]);
            String neighbours = NEIGHBOURS.get(lower);
            if (neighbours == null || neighbours.isEmpty()) {
                continue;
            }
            points[at] = neighbours.charAt(random.nextInt(neighbours.length()));
            return new String(points, 0, points.length);
        }
        return text;
    }

    /**
     * Опечатка и слово, которое из-за неё пострадало.
     *
     * @param text     текст с опечаткой
     * @param original как это слово должно выглядеть; пусто, если опечатки нет
     */
    public record Slip(String text, String original) {
        public boolean happened() {
            return !original.isEmpty();
        }
    }

    /**
     * Ошибки бывают двух родов, и каждая может случиться сама по себе: изредка
     * выходит и то, и другое сразу — как у живой руки.
     */
    public static Slip maybeAdd(String text, double probability, RandomGenerator random) {
        if (text.isBlank()) {
            return new Slip(text, "");
        }
        String mangled = text;
        if (random.nextDouble() < probability) {
            mangled = swapAdjacent(mangled, random);
        }
        if (random.nextDouble() < probability) {
            mangled = hitNeighbourKey(mangled, random);
        }
        return new Slip(mangled, mangled.equals(text) ? "" : differingWord(text, mangled));
    }

    /** Находит слово, которое различается между исходным текстом и испорченным. */
    private static String differingWord(String original, String mangled) {
        String[] before = original.split(" ", -1);
        String[] after = mangled.split(" ", -1);
        if (before.length != after.length) {
            return "";
        }
        for (int i = 0; i < before.length; i++) {
            if (!before[i].equals(after[i])) {
                return before[i];
            }
        }
        return "";
    }
}
