package io.floci.az.core.storage;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Generic storage abstraction. Implementations: memory, wal, persistent, hybrid.
 *
 * @param <K> key type
 * @param <V> value type
 */
public interface StorageBackend<K, V> {

    void put(K key, V value);

    Optional<V> get(K key);

    void delete(K key);

    List<V> scan(Predicate<K> keyFilter);

    Set<K> keys();

    void flush();

    void load();

    void clear();
}
