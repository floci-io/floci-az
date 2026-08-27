package io.floci.az.core.arm;

import java.util.List;
import java.util.Map;

/**
 * A service that registers its resources in ARM's resource-group index —
 * {@code GET subscriptions/{sub}/resourceGroups/{rg}/resources}.
 *
 * <p>The azurerm provider calls that listing before deleting a resource group to verify it is
 * empty; a service that skips registration lets {@code terraform destroy} remove a group whose
 * resources still exist. {@code ArmHandler} discovers implementations via CDI
 * {@code Instance<ResourceIndexContributor>} (the same pattern as {@code Resettable} /
 * {@code AdminController}), so contributing costs one interface — not an ArmHandler constructor
 * change per service.</p>
 */
public interface ResourceIndexContributor {

    /**
     * ARM resource maps ({@code id}, {@code name}, {@code type}, {@code location}, optional
     * {@code tags}) for this service's resources in the given resource group.
     */
    List<Map<String, Object>> listRgResources(String sub, String rg);

    /** Whether this contributor's service is enabled; disabled services contribute nothing. */
    default boolean indexEnabled() {
        return true;
    }
}
