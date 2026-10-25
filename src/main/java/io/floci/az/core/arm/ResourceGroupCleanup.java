package io.floci.az.core.arm;

/**
 * A service that keeps state under a resource group that is not a resource of its own, and so is not in the
 * generic resource index the delete cascade walks: deployment history, for one. {@code ResourceGroupService}
 * discovers implementations through CDI {@code Instance<ResourceGroupCleanup>}, as {@code ArmHandler} discovers
 * {@link ResourceIndexContributor}s, and tells each one once a group is gone, whichever delete semantics ran.
 */
public interface ResourceGroupCleanup {

    /** Forgets what this service keeps under the group; called after the group has been removed. */
    void resourceGroupDeleted(String subscriptionId, String resourceGroup);
}
