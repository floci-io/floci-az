package io.floci.az.core.directory;

import io.floci.az.core.Resettable;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Every resource group, keyed case-insensitively by subscription and name, as ARM matches them. Groups live in
 * memory, as they always have: the docs promise that ARM state does not survive a restart.
 */
@ApplicationScoped
public class ResourceGroups implements Resettable {

    /** Guarded by {@code this}; keyed by {@link #key}, in creation order. */
    private final Map<String, ResourceGroup> groups = new LinkedHashMap<>();

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
        return Optional.of(updated);
    }

    /** Removes a group; the removed group, or empty when there was none. */
    public synchronized Optional<ResourceGroup> remove(String subscriptionId, String name) {
        String key = key(subscriptionId, name);
        return Optional.ofNullable(groups.remove(key));
    }

    @Override
    public synchronized void clear() {
        groups.clear();
    }
}
