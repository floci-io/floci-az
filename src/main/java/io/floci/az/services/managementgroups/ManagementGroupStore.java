package io.floci.az.services.managementgroups;

import io.floci.az.core.Resettable;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.Subscription;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The management-group hierarchy of a tenant: the tenant root group every tenant has, the groups under
 * it, and which group each subscription belongs to. A subscription that was never placed belongs to the
 * tenant root group, as in Azure.
 */
@ApplicationScoped
public class ManagementGroupStore implements Resettable {

    /** A group as stored; {@code parent} is null for the tenant root group only. */
    public record Group(String name, String displayName, String tenantId, String parent, int version,
                        Instant updatedTime, String updatedBy) {

        public String id() {
            return "/providers/Microsoft.Management/managementGroups/" + name;
        }
    }

    private final AzureDirectory directory;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<String, String> subscriptionParents = new LinkedHashMap<>();

    @Inject
    public ManagementGroupStore(AzureDirectory directory) {
        this.directory = directory;
    }

    /** The tenant root group, created on first use and named after the tenant like Azure's. */
    public synchronized Group root() {
        String tenantId = directory.defaultTenantId();
        return groups.computeIfAbsent(key(tenantId), k ->
            new Group(tenantId, "Tenant Root Group", tenantId, null, 1, Instant.now(), null));
    }

    public synchronized Optional<Group> get(String name) {
        root();
        return Optional.ofNullable(groups.get(key(name)));
    }

    public synchronized List<Group> list() {
        root();
        return new ArrayList<>(groups.values());
    }

    public synchronized Group put(Group group) {
        root();
        groups.put(key(group.name()), group);
        return group;
    }

    public synchronized Optional<Group> remove(String name) {
        return Optional.ofNullable(groups.remove(key(name)));
    }

    /** The groups whose parent is {@code name}, in creation order. */
    public synchronized List<Group> childGroups(String name) {
        List<Group> out = new ArrayList<>();
        for (Group group : groups.values()) {
            if (group.parent() != null && group.parent().equalsIgnoreCase(name)) {
                out.add(group);
            }
        }
        return out;
    }

    /** The subscriptions placed directly under {@code name}; the root also holds every unplaced subscription. */
    public synchronized List<Subscription> childSubscriptions(String name) {
        List<Subscription> out = new ArrayList<>();
        boolean isRoot = root().name().equalsIgnoreCase(name);
        for (Subscription subscription : directory.subscriptions()) {
            String parent = subscriptionParents.get(key(subscription.id()));
            if (parent == null ? isRoot : parent.equalsIgnoreCase(name)) {
                out.add(subscription);
            }
        }
        return out;
    }

    /** The group a subscription belongs to: where it was placed, else the tenant root group. */
    public synchronized Group parentOf(String subscriptionId) {
        String parent = subscriptionParents.get(key(subscriptionId));
        return parent == null ? root() : groups.getOrDefault(key(parent), root());
    }

    public synchronized boolean isPlaced(String subscriptionId) {
        return subscriptionParents.containsKey(key(subscriptionId));
    }

    public synchronized void place(String subscriptionId, String groupName) {
        directory.registerOnFirstUse(subscriptionId, null);
        subscriptionParents.put(key(subscriptionId), groupName);
    }

    public synchronized boolean unplace(String subscriptionId) {
        return subscriptionParents.remove(key(subscriptionId)) != null;
    }

    /** The ancestors of a group from its parent up to the root. */
    public synchronized List<Group> ancestors(String name) {
        List<Group> out = new ArrayList<>();
        Group current = groups.get(key(name));
        while (current != null && current.parent() != null) {
            Group parent = groups.get(key(current.parent()));
            if (parent == null) {
                break;
            }
            out.add(parent);
            current = parent;
        }
        return out;
    }

    /** True when {@code candidate} is {@code name} itself or sits below it. */
    public synchronized boolean isWithin(String candidate, String name) {
        if (candidate.equalsIgnoreCase(name)) {
            return true;
        }
        for (Group ancestor : ancestors(candidate)) {
            if (ancestor.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized void clear() {
        groups.clear();
        subscriptionParents.clear();
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
