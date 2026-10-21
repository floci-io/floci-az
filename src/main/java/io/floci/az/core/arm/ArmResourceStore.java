package io.floci.az.core.arm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A service's ARM resources of one kind, persisted through {@link StorageFactory} and keyed by the canonical
 * (lowercased) resource id, so two paths that differ only in case address one resource, as ARM ids do.
 *
 * <p>Each value carries its own id ({@code idOf}), in the casing of the request that created it, which is the
 * casing responses echo.
 *
 * <p><b>Legacy keys.</b> A store a service wrote before it used this class holds entries under other key shapes
 * (raw-case {@code sub/rg/name}, placeholders). Every key in the store that is not canonical and that
 * {@code legacyKey} accepts is a legacy entry, and its id is read from its value. Reads find legacy entries too;
 * nothing is rewritten at startup. The first {@link #put}, {@link #update} or {@link #delete} of such a resource
 * moves it to its canonical key (move-on-write), so an entry never exists twice.
 *
 * @param <T> the stored model, Jackson-serializable and reflection-registered for the native image
 */
public final class ArmResourceStore<T> {

    private static final Logger LOG = Logger.getLogger(ArmResourceStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** One resource and its id. */
    public record Stored<T>(ArmResourceId id, T value, Instant lastModified) {
    }

    private final String name;
    private final StorageBackend<String, StoredObject> backend;
    private final Class<T> type;
    private final Function<T, String> idOf;
    private final Predicate<String> legacyKey;

    private ArmResourceStore(String name, StorageBackend<String, StoredObject> backend, Class<T> type,
                             Function<T, String> idOf, Predicate<String> legacyKey) {
        this.name = name;
        this.backend = backend;
        this.type = type;
        this.idOf = idOf;
        this.legacyKey = legacyKey;
    }

    /**
     * A store over the {@link StorageFactory} backend {@code storeName}, so persistence follows that name's mode.
     *
     * @param idOf      the ARM id a value belongs to
     * @param legacyKey which non-canonical keys of the backend hold this store's legacy entries
     */
    public static <T> ArmResourceStore<T> create(StorageFactory factory, String storeName, Class<T> type,
                                                 Function<T, String> idOf, Predicate<String> legacyKey) {
        return over(factory.create(storeName), storeName, type, idOf, legacyKey);
    }

    /** A store over {@code backend}, for tests. */
    static <T> ArmResourceStore<T> over(StorageBackend<String, StoredObject> backend, String storeName, Class<T> type,
                                        Function<T, String> idOf, Predicate<String> legacyKey) {
        return new ArmResourceStore<>(storeName, backend, type, idOf, legacyKey);
    }

    /** The resource {@code id} names, in canonical or legacy form. */
    public synchronized Optional<Stored<T>> get(ArmResourceId id) {
        Optional<Stored<T>> canonical = backend.get(id.canonicalKey()).flatMap(this::decode);
        return canonical.isPresent() ? canonical : legacy(id).map(Map.Entry::getValue);
    }

    /** Writes {@code value} under its canonical key, removing a legacy entry for the same resource. */
    public synchronized Stored<T> put(T value) {
        ArmResourceId id = idFor(value);
        legacy(id).ifPresent(entry -> backend.delete(entry.getKey()));
        Instant now = Instant.now();
        backend.put(id.canonicalKey(), new StoredObject(id.canonicalKey(), encode(value), Map.of(), now,
            UUID.randomUUID().toString()));
        return new Stored<>(id, value, now);
    }

    /**
     * Applies {@code change} to the stored resource and writes the result, atomically with respect to every other
     * write to this store; empty when there is no such resource.
     */
    public synchronized Optional<Stored<T>> update(ArmResourceId id, UnaryOperator<T> change) {
        return get(id).map(stored -> put(change.apply(stored.value())));
    }

    /** Removes the resource, canonical and legacy entry alike; empty when there was none. */
    public synchronized Optional<Stored<T>> delete(ArmResourceId id) {
        Optional<Stored<T>> found = get(id);
        backend.delete(id.canonicalKey());
        legacy(id).ifPresent(entry -> backend.delete(entry.getKey()));
        return found;
    }

    /**
     * The resources in {@code scope}: the whole subscription when it names no resource group, else that group.
     * Subscriptions and groups compare case-insensitively; canonical entries win on a clash.
     */
    public synchronized List<Stored<T>> list(ArmScope scope) {
        return all().stream().filter(stored -> inScope(stored.id(), scope)).toList();
    }

    private static boolean inScope(ArmResourceId id, ArmScope scope) {
        return scope.subscription().equalsIgnoreCase(id.subscriptionId())
            && (scope.resourceGroup() == null || scope.resourceGroup().equalsIgnoreCase(id.resourceGroup()));
    }

    /** Every resource in the store, canonical entries first and each resource once. */
    public synchronized List<Stored<T>> all() {
        Map<String, Stored<T>> byKey = new LinkedHashMap<>();
        List<Stored<T>> legacyEntries = new ArrayList<>();
        for (String key : backend.keys()) {
            if (isCanonical(key)) {
                backend.get(key).flatMap(this::decode).ifPresent(stored -> byKey.put(key, stored));
            } else if (legacyKey.test(key)) {
                backend.get(key).flatMap(this::decode).ifPresent(legacyEntries::add);
            }
        }
        for (Stored<T> stored : legacyEntries) {
            byKey.putIfAbsent(stored.id().canonicalKey(), stored);
        }
        return List.copyOf(byKey.values());
    }

    /** Removes every entry this store owns, legacy ones included: {@code POST /_admin/reset}. */
    public synchronized void clear() {
        for (String key : backend.keys()) {
            if (isCanonical(key) || legacyKey.test(key)) {
                backend.delete(key);
            }
        }
    }

    /** The legacy entry for {@code id}: a non-canonical key whose value belongs to it. */
    private Optional<Map.Entry<String, Stored<T>>> legacy(ArmResourceId id) {
        for (String key : backend.keys()) {
            if (isCanonical(key) || !legacyKey.test(key)) {
                continue;
            }
            Optional<Stored<T>> stored = backend.get(key).flatMap(this::decode);
            if (stored.isPresent() && stored.get().id().canonicalKey().equals(id.canonicalKey())) {
                return Optional.of(Map.entry(key, stored.get()));
            }
        }
        return Optional.empty();
    }

    private static boolean isCanonical(String key) {
        return key.startsWith("/");
    }

    private ArmResourceId idFor(T value) {
        String id = idOf.apply(value);
        return ArmResourceId.parse(id).orElseThrow(() -> new IllegalArgumentException(
            "store " + name + ": the value's id '" + id + "' is not an ARM resource id"));
    }

    private Optional<Stored<T>> decode(StoredObject object) {
        try {
            T value = MAPPER.readValue(object.data(), type);
            return Optional.of(new Stored<>(idFor(value), value, object.lastModified()));
        } catch (IOException | IllegalArgumentException e) {
            LOG.warnv("Store {0}: skipping the unreadable entry {1}: {2}", name, object.key(), e.getMessage());
            return Optional.empty();
        }
    }

    private byte[] encode(T value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new IllegalStateException("store " + name + ": cannot serialize " + type.getSimpleName(), e);
        }
    }
}
