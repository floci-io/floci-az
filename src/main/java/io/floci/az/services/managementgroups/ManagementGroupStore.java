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
 * The management-group hierarchies of the directory's tenants, one per tenant: the tenant root group every
 * tenant has, the groups under it, and which group each of the tenant's subscriptions belongs to. A
 * subscription that was never placed belongs to its tenant root group, as in Azure. Every operation names
 * the tenant whose hierarchy it works on, which the handler resolves from the caller's token.
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

    /** A hierarchy change the store refuses, with the ARM error it maps to. */
    public static final class HierarchyException extends RuntimeException {
        private final int status;
        private final String code;

        HierarchyException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    /**
     * One tenant's hierarchy as it was at one instant: its groups and subscription placements, read under the
     * store's lock in a single step, so a reader that walks parents and ancestors sees one consistent tree
     * whatever moves land while it works.
     */
    public record Snapshot(String tenantId, Map<String, Group> groups, Map<String, String> subscriptionParents) {

        public Group root() {
            return groups.get(key(tenantId));
        }

        public Optional<Group> get(String name) {
            return Optional.ofNullable(groups.get(key(name)));
        }

        /** Every group, the tenant root first, in creation order. */
        public List<Group> list() {
            return new ArrayList<>(groups.values());
        }

        /** The group a subscription belongs to: where it was placed, else the tenant root group. */
        public Group parentOf(String subscriptionId) {
            String parent = subscriptionParents.get(key(subscriptionId));
            return parent == null ? root() : groups.getOrDefault(key(parent), root());
        }

        /** The ancestors of a group from its parent up to the root. */
        public List<Group> ancestors(String name) {
            List<Group> out = new ArrayList<>();
            Group current = groups.get(key(name));
            while (current != null && current.parent() != null && out.size() <= groups.size()) {
                Group parent = groups.get(key(current.parent()));
                if (parent == null) {
                    break;
                }
                out.add(parent);
                current = parent;
            }
            return out;
        }
    }

    /** One tenant's groups and the placements of its subscriptions, both keyed case-insensitively. */
    private static final class Hierarchy {
        private final Map<String, Group> groups = new LinkedHashMap<>();
        private final Map<String, String> subscriptionParents = new LinkedHashMap<>();
    }

    private final AzureDirectory directory;
    private final Map<String, Hierarchy> hierarchies = new LinkedHashMap<>();

    @Inject
    public ManagementGroupStore(AzureDirectory directory) {
        this.directory = directory;
    }

    /** The tenant root group, created on first use and named after the tenant like Azure's. */
    public synchronized Group root(String tenantId) {
        return hierarchy(tenantId).groups.get(key(tenantId));
    }

    public synchronized Optional<Group> get(String tenantId, String name) {
        return Optional.ofNullable(hierarchy(tenantId).groups.get(key(name)));
    }

    public synchronized List<Group> list(String tenantId) {
        return new ArrayList<>(hierarchy(tenantId).groups.values());
    }

    /**
     * Creates a group under {@code parentName} (the root when null) or updates its display name and parent,
     * checking the parent and the hierarchy under the store's lock so that concurrent moves cannot form a cycle.
     */
    public synchronized Group createOrUpdate(String tenantId, String name, String displayName, String parentName) {
        Hierarchy hierarchy = hierarchy(tenantId);
        Group root = root(tenantId);
        if (name.equalsIgnoreCase(root.name())) {
            throw new HierarchyException(400, "InvalidRequest", "The tenant root management group cannot be created or updated.");
        }
        String parent = checkedParent(hierarchy, name, parentName == null ? root.name() : parentName);
        Group existing = hierarchy.groups.get(key(name));
        Group group = new Group(name, displayName != null ? displayName : existing != null ? existing.displayName() : name,
            root.tenantId(), parent, existing == null ? 1 : existing.version() + 1, Instant.now(), null);
        hierarchy.groups.put(key(name), group);
        return group;
    }

    /** Moves or renames an existing group; empty when there is none by that name. */
    public synchronized Optional<Group> move(String tenantId, String name, String displayName, String parentName) {
        Hierarchy hierarchy = hierarchy(tenantId);
        Group existing = hierarchy.groups.get(key(name));
        if (existing == null) {
            return Optional.empty();
        }
        if (existing.parent() == null) {
            throw new HierarchyException(400, "InvalidRequest", "The tenant root management group cannot be updated.");
        }
        String parent = parentName == null ? existing.parent() : checkedParent(hierarchy, name, parentName);
        Group group = new Group(name, displayName != null ? displayName : existing.displayName(), existing.tenantId(), parent,
            existing.version() + 1, Instant.now(), null);
        hierarchy.groups.put(key(name), group);
        return Optional.of(group);
    }

    /** Deletes an empty group; false when there is none by that name. */
    public synchronized boolean delete(String tenantId, String name) {
        Hierarchy hierarchy = hierarchy(tenantId);
        Group existing = hierarchy.groups.get(key(name));
        if (existing == null) {
            return false;
        }
        if (existing.parent() == null) {
            throw new HierarchyException(400, "InvalidRequest", "The tenant root management group cannot be deleted.");
        }
        if (!childGroups(tenantId, name).isEmpty() || !childSubscriptions(tenantId, name).isEmpty()) {
            throw new HierarchyException(409, "Conflict",
                "The management group '" + name + "' has children and cannot be deleted. Move or delete its children first.");
        }
        hierarchy.groups.remove(key(name));
        return true;
    }

    private String checkedParent(Hierarchy hierarchy, String name, String parentName) {
        Group parent = hierarchy.groups.get(key(parentName));
        if (parent == null) {
            throw new HierarchyException(400, "InvalidRequest", "The parent management group '" + parentName + "' could not be found.");
        }
        if (isWithin(hierarchy, parent.name(), name)) {
            throw new HierarchyException(400, "InvalidRequest", "A management group cannot be moved under itself or one of its descendants.");
        }
        return parent.name();
    }

    /** The groups whose parent is {@code name}, in creation order. */
    public synchronized List<Group> childGroups(String tenantId, String name) {
        List<Group> out = new ArrayList<>();
        for (Group group : hierarchy(tenantId).groups.values()) {
            if (group.parent() != null && group.parent().equalsIgnoreCase(name)) {
                out.add(group);
            }
        }
        return out;
    }

    /** The subscriptions of the hierarchy's tenant; other tenants have hierarchies of their own. */
    public synchronized List<Subscription> tenantSubscriptions(String tenantId) {
        return directory.subscriptionsOfTenant(root(tenantId).tenantId());
    }

    /** The subscriptions placed directly under {@code name}; the root also holds every unplaced subscription of its tenant. */
    public synchronized List<Subscription> childSubscriptions(String tenantId, String name) {
        Hierarchy hierarchy = hierarchy(tenantId);
        List<Subscription> out = new ArrayList<>();
        boolean isRoot = root(tenantId).name().equalsIgnoreCase(name);
        for (Subscription subscription : tenantSubscriptions(tenantId)) {
            String parent = hierarchy.subscriptionParents.get(key(subscription.id()));
            if (parent == null ? isRoot : parent.equalsIgnoreCase(name)) {
                out.add(subscription);
            }
        }
        return out;
    }

    /** The group a subscription belongs to: where it was placed, else the tenant root group. */
    public synchronized Group parentOf(String tenantId, String subscriptionId) {
        Hierarchy hierarchy = hierarchy(tenantId);
        String parent = hierarchy.subscriptionParents.get(key(subscriptionId));
        return parent == null ? root(tenantId) : hierarchy.groups.getOrDefault(key(parent), root(tenantId));
    }

    public synchronized boolean isPlaced(String tenantId, String subscriptionId) {
        return hierarchy(tenantId).subscriptionParents.containsKey(key(subscriptionId));
    }

    /**
     * Places a subscription under a group of its own tenant, registering an unknown subscription in that tenant.
     * The directory's first-use registration returns a subscription it already holds unchanged, so the tenant
     * check reads the subscription that registration returned: one that another tenant registered in the
     * meantime is refused, not placed in the wrong hierarchy.
     */
    public synchronized Subscription place(String tenantId, String subscriptionId, String groupName) {
        Hierarchy hierarchy = hierarchy(tenantId);
        Group group = hierarchy.groups.get(key(groupName));
        if (group == null) {
            throw new HierarchyException(404, "NotFound", "The management group '" + groupName + "' could not be found.");
        }
        Subscription subscription = directory.registerOnFirstUse(subscriptionId, group.tenantId());
        if (!subscription.tenantId().equalsIgnoreCase(group.tenantId())) {
            throw new HierarchyException(400, "InvalidRequest",
                "The subscription '" + subscriptionId + "' belongs to tenant '" + subscription.tenantId()
                    + "' and cannot be placed in the management group hierarchy of tenant '" + group.tenantId() + "'.");
        }
        hierarchy.subscriptionParents.put(key(subscriptionId), group.name());
        return subscription;
    }

    /**
     * Removes a subscription's placement only while it is still under {@code groupName}, in one locked step, so a
     * delete addressed to one group cannot undo a move to another that landed in between. False when the
     * subscription is not placed there.
     */
    public synchronized boolean unplace(String tenantId, String subscriptionId, String groupName) {
        Hierarchy hierarchy = hierarchy(tenantId);
        String current = hierarchy.subscriptionParents.get(key(subscriptionId));
        if (current == null || !current.equalsIgnoreCase(groupName)) {
            return false;
        }
        hierarchy.subscriptionParents.remove(key(subscriptionId));
        return true;
    }

    /** A consistent copy of the tenant's hierarchy, for readers that derive several rows from one tree. */
    public synchronized Snapshot snapshot(String tenantId) {
        Hierarchy hierarchy = hierarchy(tenantId);
        return new Snapshot(tenantId, Map.copyOf(hierarchy.groups), Map.copyOf(hierarchy.subscriptionParents));
    }

    /** The ancestors of a group from its parent up to the root. */
    public synchronized List<Group> ancestors(String tenantId, String name) {
        return ancestors(hierarchy(tenantId), name);
    }

    private static List<Group> ancestors(Hierarchy hierarchy, String name) {
        List<Group> out = new ArrayList<>();
        Group current = hierarchy.groups.get(key(name));
        while (current != null && current.parent() != null && out.size() <= hierarchy.groups.size()) {
            Group parent = hierarchy.groups.get(key(current.parent()));
            if (parent == null) {
                break;
            }
            out.add(parent);
            current = parent;
        }
        return out;
    }

    /** True when {@code candidate} is {@code name} itself or sits below it. */
    public synchronized boolean isWithin(String tenantId, String candidate, String name) {
        return isWithin(hierarchy(tenantId), candidate, name);
    }

    private static boolean isWithin(Hierarchy hierarchy, String candidate, String name) {
        if (candidate.equalsIgnoreCase(name)) {
            return true;
        }
        for (Group ancestor : ancestors(hierarchy, candidate)) {
            if (ancestor.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized void clear() {
        hierarchies.clear();
    }

    /** The tenant's hierarchy, created with its root group on first use. */
    private Hierarchy hierarchy(String tenantId) {
        return hierarchies.computeIfAbsent(key(tenantId), k -> {
            Hierarchy hierarchy = new Hierarchy();
            hierarchy.groups.put(key(tenantId), new Group(tenantId, "Tenant Root Group", tenantId, null, 1, Instant.now(), null));
            return hierarchy;
        });
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
