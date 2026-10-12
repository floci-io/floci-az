package io.floci.az.core.directory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Every resource group, keyed case-insensitively by subscription and name, as ARM matches them.
 *
 * <p>Groups live in memory, as they always have, unless {@link EmulatorConfig#resourceGroupSemantics()} is on:
 * then they are also written through to the {@link AzureDirectory#STORE directory} store, so they persist
 * under the directory's storage mode alongside the tenants and subscriptions they belong to. Keeping them in
 * memory by default is deliberate: the docs promise that ARM state does not survive a restart, and a group
 * that did while the resources the {@code ArmHandler} holds in it did not would be a change a client could
 * notice.
 */
@ApplicationScoped
public class ResourceGroups implements Resettable {

    private static final Logger LOG = Logger.getLogger(ResourceGroups.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIX = "resourcegroup/";

    /** Null when groups are memory-only. */
    private final StorageBackend<String, StoredObject> store;

    /** Guarded by {@code this}; keyed by {@link #key}, in creation order. */
    private final Map<String, ResourceGroup> groups = new LinkedHashMap<>();

    /** Guarded by {@code this}; the deletes in progress per {@link #key}. Never persisted. */
    private final Map<String, Integer> deleting = new HashMap<>();

    /** Guarded by {@code this}; the admitted writes still running per {@link #key}. Never persisted. */
    private final Map<String, Integer> writing = new HashMap<>();

    @Inject
    public ResourceGroups(EmulatorConfig config, StorageFactory storageFactory) {
        this(config.resourceGroupSemantics() ? storageFactory.create(AzureDirectory.STORE) : null);
    }

    /** For tests: groups over an explicit backend, or memory-only for a null one. */
    public ResourceGroups(StorageBackend<String, StoredObject> store) {
        this.store = store;
        load();
    }

    /** The key two spellings of one group share. */
    static String key(String subscriptionId, String name) {
        return subscriptionId.toLowerCase(Locale.ROOT) + "/" + name.toLowerCase(Locale.ROOT);
    }

    public synchronized Optional<ResourceGroup> get(String subscriptionId, String name) {
        return Optional.ofNullable(groups.get(key(subscriptionId, name)));
    }

    public boolean exists(String subscriptionId, String name) {
        return get(subscriptionId, name).isPresent();
    }

    /** The groups of one subscription, in creation order. */
    public synchronized List<ResourceGroup> list(String subscriptionId) {
        String prefix = subscriptionId.toLowerCase(Locale.ROOT) + "/";
        return groups.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(prefix))
            .map(Map.Entry::getValue)
            .toList();
    }

    /** Creates or replaces a group; true when it did not exist before. */
    public synchronized boolean put(ResourceGroup group) {
        String key = key(group.subscriptionId(), group.name());
        boolean created = !groups.containsKey(key);
        groups.put(key, group);
        persist(key, group);
        return created;
    }

    /** Creates the group unless it exists, and returns the group that exists afterwards. */
    public synchronized ResourceGroup createIfAbsent(String subscriptionId, String name, String location) {
        String key = key(subscriptionId, name);
        ResourceGroup existing = groups.get(key);
        if (existing != null) {
            return existing;
        }
        ResourceGroup created = new ResourceGroup(subscriptionId, name, location, null, null,
            ResourceGroup.SUCCEEDED);
        groups.put(key, created);
        persist(key, created);
        LOG.infov("Created resource group {0} for a resource PUT under it", name);
        return created;
    }

    /** Applies {@code change} to an existing group; empty when there is none. */
    public synchronized Optional<ResourceGroup> update(String subscriptionId, String name,
                                                       UnaryOperator<ResourceGroup> change) {
        String key = key(subscriptionId, name);
        ResourceGroup existing = groups.get(key);
        if (existing == null) {
            return Optional.empty();
        }
        ResourceGroup updated = change.apply(existing);
        groups.put(key, updated);
        persist(key, updated);
        return Optional.of(updated);
    }

    /** Removes a group; the removed group, or empty when there was none. */
    public synchronized Optional<ResourceGroup> remove(String subscriptionId, String name) {
        String key = key(subscriptionId, name);
        ResourceGroup removed = groups.remove(key);
        if (removed != null && store != null) {
            store.delete(PREFIX + key);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Admits a write of a resource under a group, unless the group is being deleted. Every admitted write must
     * end with {@link #endWrite}, which is what lets {@link #beginDelete} wait for it.
     *
     * @return false when a delete of the group is in progress
     */
    public synchronized boolean beginWrite(String subscriptionId, String name) {
        String key = key(subscriptionId, name);
        if (deleting.containsKey(key)) {
            return false;
        }
        writing.merge(key, 1, Integer::sum);
        return true;
    }

    /** Ends a write {@link #beginWrite} admitted. */
    public synchronized void endWrite(String subscriptionId, String name) {
        release(writing, key(subscriptionId, name));
        notifyAll();
    }

    /**
     * Marks an existing group as being deleted, so no further write under it is admitted, then waits for the
     * writes admitted earlier to end. On return every resource a write could have created in the group is
     * visible to the caller, and none can appear until {@link #endDelete}. A write under the group ends on its
     * own request thread, so the wait is as long as the slowest handler already running.
     *
     * @return false when there is no such group, in which case nothing is marked
     */
    public synchronized boolean beginDelete(String subscriptionId, String name) {
        String key = key(subscriptionId, name);
        if (!groups.containsKey(key)) {
            return false;
        }
        deleting.merge(key, 1, Integer::sum);
        try {
            while (writing.containsKey(key)) {
                wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            release(deleting, key);
            throw new IllegalStateException("Interrupted waiting for writes under resource group " + name, e);
        }
        return true;
    }

    /** Ends a delete {@link #beginDelete} marked, whether or not it removed the group. */
    public synchronized void endDelete(String subscriptionId, String name) {
        release(deleting, key(subscriptionId, name));
    }

    private static void release(Map<String, Integer> counts, String key) {
        counts.computeIfPresent(key, (ignored, count) -> count > 1 ? count - 1 : null);
    }

    @Override
    public synchronized void clear() {
        if (store != null) {
            for (String key : store.keys().stream().filter(k -> k.startsWith(PREFIX)).toList()) {
                store.delete(key);
            }
        }
        groups.clear();
    }

    private synchronized void load() {
        groups.clear();
        if (store == null) {
            return;
        }
        List<String> keys = new ArrayList<>(store.keys().stream().filter(k -> k.startsWith(PREFIX)).toList());
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            store.get(key).ifPresent(stored -> read(key, stored)
                .ifPresent(group -> groups.put(key.substring(PREFIX.length()), group)));
        }
        LOG.debugv("Resource groups: {0} loaded from the directory store", groups.size());
    }

    private static Optional<ResourceGroup> read(String key, StoredObject stored) {
        try {
            return Optional.of(MAPPER.readValue(stored.data(), ResourceGroup.class));
        } catch (IOException e) {
            LOG.warnv(e, "Resource groups: skipping unreadable entry {0}", key);
            return Optional.empty();
        }
    }

    private void persist(String key, ResourceGroup group) {
        if (store == null) {
            return;
        }
        try {
            store.put(PREFIX + key, new StoredObject(PREFIX + key, MAPPER.writeValueAsBytes(group), Map.of(),
                Instant.now(), UUID.randomUUID().toString()));
        } catch (IOException e) {
            throw new IllegalStateException("Resource groups: cannot serialize " + key, e);
        }
    }
}
