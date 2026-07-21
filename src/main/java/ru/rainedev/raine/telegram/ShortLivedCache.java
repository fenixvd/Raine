package ru.rainedev.raine.telegram;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Ответы Telegram, которые за один ход спрашиваются по нескольку раз.
 * <p>
 * Число обращений к Telegram ограничено, а одно и то же сообщение проверяется
 * и на цитату, и на пересылку, и на правку. Держать такое вечно нельзя —
 * текст правят, — поэтому запись живёт считанные секунды: внутри одного хода
 * это один запрос вместо пяти, а между ходами данные всегда свежие.
 *
 * @param <K> ключ
 * @param <V> что хранится
 */
final class ShortLivedCache<K, V> {

    private record Entry<V>(V value, long at) {}

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final long lifetimeMillis;

    ShortLivedCache(long lifetimeMillis) {
        this.lifetimeMillis = lifetimeMillis;
    }

    /** Считает значение, если его нет или оно устарело. */
    V get(K key, Function<K, V> compute) {
        Entry<V> entry = entries.get(key);
        if (entry != null && System.currentTimeMillis() - entry.at() < lifetimeMillis) {
            return entry.value();
        }
        V value = compute.apply(key);
        if (value != null) {
            entries.put(key, new Entry<>(value, System.currentTimeMillis()));
        } else {
            entries.remove(key);
        }
        return value;
    }

    /** То же для запросов, которые могут ничего не вернуть. */
    Optional<V> optional(K key, Function<K, Optional<V>> compute) {
        return Optional.ofNullable(get(key, k -> compute.apply(k).orElse(null)));
    }

    void forget(K key) {
        entries.remove(key);
    }

    void clear() {
        entries.clear();
    }
}
