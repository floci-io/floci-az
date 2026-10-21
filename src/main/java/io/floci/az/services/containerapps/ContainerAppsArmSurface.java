package io.floci.az.services.containerapps;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link ContainerAppsHandler} serves. */
@ApplicationScoped
public class ContainerAppsArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.App";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "containerApps", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "containerApps/revisions"),
            ArmResourceType.resourceGroup(NAMESPACE, "managedEnvironments", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "managedEnvironments/storages"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
