package io.floci.az.services.arm;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link ArmHandler} serves. */
@ApplicationScoped
public class WebArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Web";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.ARM_HANDLER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "sites", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "sites/config"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
