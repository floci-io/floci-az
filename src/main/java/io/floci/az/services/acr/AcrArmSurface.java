package io.floci.az.services.acr;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link AcrHandler} serves. */
@ApplicationScoped
public class AcrArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.ContainerRegistry";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "registries", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "registries/replications"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
