package io.floci.az.services.aks;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link AksHandler} serves. */
@ApplicationScoped
public class AksArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.ContainerService";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "managedClusters", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "managedClusters/agentPools"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
