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
import java.util.Comparator;
import java.util.HashMap;
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
 * moves it to its canonical key (move-on-write), so an entry never exists twice. A service that keyed by the raw
 * request path can hold one resource under several legacy keys that differ only in case; a write or a delete
 * removes all of them, and a read takes the most recently written one.
 *
 * <p>Legacy entries only come from data a service wrote before it used this class, so their number only shrinks.
 * The store indexes them by canonical key on first use, and a write or a delete looks its resource up in that index
 * instead of walking every key; {@link #clear()} drops the index with the entries.
 *
 * <p><b>Shared backends.</b> A service that keeps several resource types in one backend gives each store its type
 * ({@link #create(StorageFactory, String, String, Class, Function, Predicate)}); the store then owns only the
 * canonical keys of that type, and its {@code legacyKey} tells its legacy entries apart from the other types'.
 *
 * @param <T> the stored model, Jackson-serializable and reflection-registered for the native image
 */
public final class ArmResourceStore<T> {

    private static final Logger LOG = Logger.getLogger(ArmResourceStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** Older first; an entry written without a timestamp counts as the oldest. */
    private static final Comparator<Stored<?>> BY_LAST_MODIFIED =
        Comparator.comparing(Stored::lastModified, Comparator.nullsFirst(Comparator.naturalOrder()));

    /** One resource and its id. */
    public record Stored<T>(ArmResourceId id, T value, Instant lastModified) {
    }

    /** What {@link #createOrUpdate} wrote, and whether the resource was new. */
    public record Written<T>(Stored<T> stored, boolean created) {
    }

    private final String name;
    private final StorageBackend<String, StoredObject> backend;
    private final Class<T> type;
    private final Function<T, String> idOf;
    private final Predicate<String> legacyKey;
    /** Legacy backend keys by the canonical key of the resource they hold; null until first needed. */
    private Map<String, List<String>> legacyIndex;
    /** The resource type whose canonical keys this store owns, or null for every canonical key. */
    private final String resourceType;

    private ArmResourceStore(String name, StorageBackend<String, StoredObject> backend, String resourceType,
                             Class<T> type, Function<T, String> idOf, Predicate<String> legacyKey) {
        this.name = name;
        this.backend = backend;
        this.resourceType = resourceType;
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
        return new ArmResourceStore<>(storeName, factory.create(storeName), null, type, idOf, legacyKey);
    }

    /**
     * A store for one {@code resourceType} (such as {@code Microsoft.App/containerApps}) of a backend that holds
     * other types too: it owns only the canonical keys of that type.
     */
    public static <T> ArmResourceStore<T> create(StorageFactory factory, String storeName, String resourceType,
                                                 Class<T> type, Function<T, String> idOf, Predicate<String> legacyKey) {
        return new ArmResourceStore<>(storeName, factory.create(storeName), resourceType, type, idOf, legacyKey);
    }

    /** A store over {@code backend}, for tests. */
    static <T> ArmResourceStore<T> over(StorageBackend<String, StoredObject> backend, String storeName, Class<T> type,
                                        Function<T, String> idOf, Predicate<String> legacyKey) {
        return new ArmResourceStore<>(storeName, backend, null, type, idOf, legacyKey);
    }

    /** A store of one {@code resourceType} over {@code backend}, for tests. */
    static <T> ArmResourceStore<T> over(StorageBackend<String, StoredObject> backend, String storeName,
                                        String resourceType, Class<T> type, Function<T, String> idOf,
                                        Predicate<String> legacyKey) {
        return new ArmResourceStore<>(storeName, backend, resourceType, type, idOf, legacyKey);
    }

    /** The resource {@code id} names, in canonical or legacy form. */
    public synchronized Optional<Stored<T>> get(ArmResourceId id) {
        Optional<Stored<T>> canonical = backend.get(id.canonicalKey()).flatMap(this::decode);
        return canonical.isPresent() ? canonical : newest(legacy(id));
    }

    /** Writes {@code value} under its canonical key, removing a legacy entry for the same resource. */
    public synchronized Stored<T> put(T value) {
        ArmResourceId id = idFor(value);
        forgetLegacy(id);
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

    /**
     * Writes {@code change} applied to the stored resource, or to empty when there is none, atomically with respect
     * to every other write to this store, so two concurrent creates of one resource leave the first one's value
     * for the second to change rather than each writing a new one. {@code change} returns a value whose id is
     * {@code id}.
     */
    public synchronized Written<T> createOrUpdate(ArmResourceId id, Function<Optional<T>, T> change) {
        Optional<T> existing = get(id).map(Stored::value);
        T value = change.apply(existing);
        ArmResourceId written = idFor(value);
        if (!written.canonicalKey().equals(id.canonicalKey())) {
            throw new IllegalArgumentException("store " + name + ": a write to " + id.id() + " returned "
                + written.id());
        }
        return new Written<>(put(value), existing.isEmpty());
    }

    /** Removes the resource, canonical and legacy entries alike; empty when there was none. */
    public synchronized Optional<Stored<T>> delete(ArmResourceId id) {
        Optional<Stored<T>> found = get(id);
        backend.delete(id.canonicalKey());
        forgetLegacy(id);
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
        Map<String, Stored<T>> newestLegacy = new LinkedHashMap<>();
        for (String key : backend.keys()) {
            if (isCanonical(key)) {
                backend.get(key).flatMap(this::decode).ifPresent(stored -> byKey.put(key, stored));
            } else if (!isAnyCanonical(key) && legacyKey.test(key)) {
                backend.get(key).flatMap(this::decode).ifPresent(stored ->
                    newestLegacy.merge(stored.id().canonicalKey(), stored, ArmResourceStore::newer));
            }
        }
        newestLegacy.forEach(byKey::putIfAbsent);
        return List.copyOf(byKey.values());
    }

    /** Removes every entry this store owns, legacy ones included: {@code POST /_admin/reset}. */
    public synchronized void clear() {
        for (String key : backend.keys()) {
            if (isCanonical(key) || (!isAnyCanonical(key) && legacyKey.test(key))) {
                backend.delete(key);
            }
        }
        legacyIndex = null;
    }

    /**
     * Removes the legacy entries no read can reach, and returns them: those of a resource that also has a canonical
     * entry, and every legacy entry of a resource but its newest. The old raw-case keys could hold one resource under
     * several casings, each a resource of its own to the service that wrote them, so a service whose values own
     * outside state (containers, volumes, ports) releases what these returned values own; nothing else would find it
     * once they are gone.
     */
    public synchronized List<Stored<T>> removeShadowedLegacy() {
        List<Stored<T>> removed = new ArrayList<>();
        for (Map.Entry<String, List<String>> resource : legacyIndex().entrySet()) {
            boolean canonical = backend.get(resource.getKey()).isPresent();
            List<Map.Entry<String, Stored<T>>> entries = new ArrayList<>();
            for (String key : resource.getValue()) {
                backend.get(key).flatMap(this::decode).ifPresent(stored -> entries.add(Map.entry(key, stored)));
            }
            Optional<Stored<T>> kept = canonical ? Optional.empty() : newest(entries);
            List<String> remaining = new ArrayList<>();
            for (Map.Entry<String, Stored<T>> entry : entries) {
                if (kept.isPresent() && kept.get() == entry.getValue()) {
                    remaining.add(entry.getKey());
                } else {
                    backend.delete(entry.getKey());
                    removed.add(entry.getValue());
                }
            }
            resource.setValue(remaining);
        }
        legacyIndex().values().removeIf(List::isEmpty);
        return removed;
    }

    /** The legacy entries for {@code id}: every non-canonical key whose value belongs to it. */
    private List<Map.Entry<String, Stored<T>>> legacy(ArmResourceId id) {
        List<Map.Entry<String, Stored<T>>> found = new ArrayList<>();
        for (String key : legacyIndex().getOrDefault(id.canonicalKey(), List.of())) {
            backend.get(key).flatMap(this::decode).ifPresent(stored -> found.add(Map.entry(key, stored)));
        }
        return found;
    }

    /** Deletes every legacy entry of {@code id} and drops it from the index. */
    private void forgetLegacy(ArmResourceId id) {
        List<String> keys = legacyIndex().remove(id.canonicalKey());
        if (keys != null) {
            keys.forEach(backend::delete);
        }
    }

    private Map<String, List<String>> legacyIndex() {
        if (legacyIndex == null) {
            legacyIndex = new HashMap<>();
            for (String key : backend.keys()) {
                if (!isAnyCanonical(key) && legacyKey.test(key)) {
                    backend.get(key).flatMap(this::decode).ifPresent(stored -> legacyIndex
                        .computeIfAbsent(stored.id().canonicalKey(), ignored -> new ArrayList<>()).add(key));
                }
            }
        }
        return legacyIndex;
    }

    /** The most recently written of {@code entries}, so a read does not depend on the backend's key order. */
    private static <T> Optional<Stored<T>> newest(List<Map.Entry<String, Stored<T>>> entries) {
        return entries.stream().map(Map.Entry::getValue).max(BY_LAST_MODIFIED);
    }

    private static <T> Stored<T> newer(Stored<T> a, Stored<T> b) {
        return BY_LAST_MODIFIED.compare(b, a) > 0 ? b : a;
    }

    /** A canonical key this store owns: any id, or one of {@link #resourceType} when the backend is shared. */
    private boolean isCanonical(String key) {
        if (!key.startsWith("/")) {
            return false;
        }
        return resourceType == null || ArmResourceId.parse(key)
            .map(id -> resourceType.equalsIgnoreCase(id.typePath()))
            .orElse(false);
    }

    /** Any canonical key, owned by this store or by another type's store over the same backend. */
    private static boolean isAnyCanonical(String key) {
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
