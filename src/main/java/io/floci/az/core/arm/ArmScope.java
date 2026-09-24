package io.floci.az.core.arm;

import java.util.Optional;

/**
 * The subscription and resource group an ARM request path addresses.
 *
 * <p>Services whose resource names are global DNS names (storage accounts, vaults, registries, database
 * servers) keep one entry per name. Before serving a path they compare the entry's owner with this
 * scope: a read or child call through another scope is a 404, and a create through another scope is a
 * name conflict. Subscription ids and resource-group names are case-insensitive in ARM.
 *
 * <p>{@code resourceGroup} is null for a subscription-level path such as
 * {@code subscriptions/{sub}/providers/{ns}/{type}/{name}}. Such a scope owns nothing: every resource
 * lives in a resource group, so a path that names none cannot be the owner's.
 */
public record ArmScope(String subscription, String resourceGroup) {

    /** The scope of a {@code subscriptions/{sub}/...} path, or empty for a path that names no subscription. */
    public static Optional<ArmScope> of(String path) {
        return ArmPaths.subscription(path)
                .map(subscription -> new ArmScope(subscription, ArmPaths.resourceGroup(path).orElse(null)));
    }

    /** Whether a resource stored under {@code ownerSubscription}/{@code ownerResourceGroup} lives in this scope. */
    public boolean owns(String ownerSubscription, String ownerResourceGroup) {
        return resourceGroup != null
                && subscription.equalsIgnoreCase(ownerSubscription)
                && resourceGroup.equalsIgnoreCase(ownerResourceGroup);
    }
}
