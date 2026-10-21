package io.floci.az.core.arm;

import java.util.List;

/**
 * Everything floci-az serves under one ARM provider namespace: the lane requests take to reach it and every
 * resource type it answers. Declared once per service by an {@link ArmSurfaceProvider}.
 *
 * @param namespace the provider namespace, such as {@code Microsoft.Cache}
 * @param lane      how a request reaches the handler
 * @param types     the resource types served, each in {@code namespace}
 */
public record ArmSurface(String namespace, ArmLane lane, List<ArmResourceType> types) {

    public ArmSurface {
        types = List.copyOf(types);
        for (ArmResourceType type : types) {
            if (!type.namespace().equals(namespace)) {
                throw new IllegalArgumentException(type.type() + " does not belong to the " + namespace + " surface");
            }
        }
    }
}
