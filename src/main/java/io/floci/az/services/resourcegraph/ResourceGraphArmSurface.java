package io.floci.az.services.resourcegraph;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * The ARM resource types {@link ResourceGraphHandler} serves: the shared queries of the namespace. The
 * query endpoint itself ({@code POST .../resources}) is an action on the provider, not a resource type.
 */
@ApplicationScoped
public class ResourceGraphArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.ResourceGraph";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "queries", LocationPolicy.GLOBAL))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
