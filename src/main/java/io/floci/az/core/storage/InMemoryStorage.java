package io.floci.az.core.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class InMemoryStorage<K, V> implements StorageBackend<K, V> {

    private final ConcurrentHashMap<K, V> store = new ConcurrentHashMap<>();

    @Override
    public void put(K key, V value) {
        store.put(key, value);
    }

    @Override
    public Optional<V> get(K key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void delete(K key) {
        store.remove(key);
    }

    @Override
    public List<V> scan(Predicate<K> keyFilter) {
        List<V> result = new ArrayList<>();
        store.forEach((k, v) -> {
            if (keyFilter.test(k)) result.add(v);
        });
        return result;
    }

    @Override
    public Set<K> keys() {
        return Collections.unmodifiableSet(store.keySet());
    }

    @Override public void flush() {}
    @Override public void load()  {}

    @Override
    public void clear() {
        store.clear();
    }
}
