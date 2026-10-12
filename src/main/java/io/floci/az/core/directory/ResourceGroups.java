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
