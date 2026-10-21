package io.floci.az.services.vm;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link VmHandler} serves. */
@ApplicationScoped
public class VmArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Compute";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "virtualMachines", LocationPolicy.REQUIRED))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
