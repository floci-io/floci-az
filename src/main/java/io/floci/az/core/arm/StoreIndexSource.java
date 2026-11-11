package io.floci.az.core.arm;

import java.util.List;
import java.util.Map;

/**
 * A {@link ResourceIndexContributor} whose resources are read by {@link ArmScope}, as an {@link ArmResourceStore} lists
 * them, so both listings come from one method and cannot drift apart.
 */
public interface StoreIndexSource extends ResourceIndexContributor {

    /** The index entries of this service's resources in {@code scope}, a resource group or a whole subscription. */
    List<Map<String, Object>> indexEntries(ArmScope scope);

    @Override
    default List<Map<String, Object>> listRgResources(String sub, String rg) {
        return indexEntries(new ArmScope(sub, rg));
    }

    @Override
    default List<Map<String, Object>> listSubscriptionResources(String sub) {
        return indexEntries(new ArmScope(sub, null));
    }
}
