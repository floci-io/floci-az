package io.floci.az.core.arm;

import java.util.List;
import java.util.Map;

/**
 * A service that registers its resources in ARM's generic resource index — both
 * {@code GET subscriptions/{sub}/resourceGroups/{rg}/resources} and
 * {@code GET subscriptions/{sub}/resources}.
 *
 * <p>The azurerm provider calls the resource-group listing before deleting a resource group to
 * verify it is empty; a service that skips registration lets {@code terraform destroy} remove a
 * group whose resources still exist. Anything enumerating an estate generically rather than
 * per-type — {@code az resource list}, the Resource Management SDKs, drift checkers — reads one
 * of these two listings too. {@code ArmHandler} discovers implementations via CDI
 * {@code Instance<ResourceIndexContributor>} (the same pattern as {@code Resettable} /
 * {@code AdminController}), so contributing costs one interface — not an ArmHandler constructor
 * change per service.</p>
 *
 * <p>Both methods are abstract on purpose. A default returning nothing would let a new service
 * fall out of one listing silently, which is the bug this interface exists to prevent.</p>
 */
public interface ResourceIndexContributor {

    /**
     * ARM resource maps ({@code id}, {@code name}, {@code type}, {@code location}, optional
     * {@code tags}) for this service's resources in the given resource group.
     */
    List<Map<String, Object>> listRgResources(String sub, String rg);

    /** The same maps for every one of this service's resources in the subscription. */
    List<Map<String, Object>> listSubscriptionResources(String sub);

    /** Whether this contributor's service is enabled; disabled services contribute nothing. */
    default boolean indexEnabled() {
        return true;
    }
}
