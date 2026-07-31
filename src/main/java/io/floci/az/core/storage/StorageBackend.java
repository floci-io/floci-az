package io.floci.az.core.storage;

import java.util.List;
import java.util.Map;
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

    /** Atomically removes and returns the value, so a concurrent caller can never observe it twice. */
    Optional<V> remove(K key);

    /** Applies related puts and deletes as one durable mutation. */
    void applyBatch(Map<K, V> puts, Set<K> deletes);

    /**
     * Return a new mutable list of values whose keys pass the filter. Callers may sort,
     * filter, or otherwise mutate the returned list without affecting the underlying store.
     */
    List<V> scan(Predicate<K> keyFilter);

    /** Return all keys in this store. */
    Set<K> keys();

    /** Persist data to disk if applicable. */
    void flush();

    /** Load data from disk on startup. */
    void load();

    /** Clear all data. */
    void clear();
}
