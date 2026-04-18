package io.floci.az.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Write-through JSON file storage. Atomic writes via temp-file rename.
 * Loads all data into memory on startup for fast reads.
 */
public class PersistentStorage<K, V> implements StorageBackend<K, V> {

    private static final Logger LOG = Logger.getLogger(PersistentStorage.class);

    private final ConcurrentHashMap<K, V> store = new ConcurrentHashMap<>();
    private final Path filePath;
    private final ObjectMapper mapper;
    private final TypeReference<Map<K, V>> typeRef;

    public PersistentStorage(Path filePath, TypeReference<Map<K, V>> typeRef) {
        this.filePath = filePath;
        this.typeRef = typeRef;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void put(K key, V value) {
        store.put(key, value);
        persistToDisk();
    }

    @Override
    public Optional<V> get(K key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void delete(K key) {
        store.remove(key);
        persistToDisk();
    }

    @Override
    public List<V> scan(Predicate<K> keyFilter) {
        return store.entrySet().stream()
                .filter(e -> keyFilter.test(e.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Override
    public Set<K> keys() {
        return Collections.unmodifiableSet(store.keySet());
    }

    @Override
    public void flush() {
        persistToDisk();
    }

    @Override
    public void load() {
        if (!Files.exists(filePath)) return;
        try {
            Map<K, V> data = mapper.readValue(filePath.toFile(), typeRef);
            store.clear();
            store.putAll(data);
            LOG.infov("Loaded {0} entries from {1}", store.size(), filePath);
        } catch (IOException e) {
            LOG.errorv(e, "Failed to load data from {0}", filePath);
        }
    }

    @Override
    public void clear() {
        store.clear();
        persistToDisk();
    }

    private synchronized void persistToDisk() {
        try {
            Files.createDirectories(filePath.getParent());
            Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), store);
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.errorv(e, "Failed to persist data to {0}", filePath);
        }
    }
}
