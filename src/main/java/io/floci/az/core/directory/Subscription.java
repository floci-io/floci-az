package io.floci.az.core.directory;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A subscription in the directory.
 *
 * @param id          the subscription id, as configured or first seen
 * @param tenantId    the owning tenant, always one the directory holds
 * @param displayName what {@code GET /subscriptions} reports as {@code displayName}
 * @param state       the subscription's state
 * @param origin      how the subscription entered the directory
 */
@RegisterForReflection
public record Subscription(String id, String tenantId, String displayName, SubscriptionState state,
                           DirectoryOrigin origin) {
}
