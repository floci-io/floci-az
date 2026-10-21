package io.floci.az.services.email;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link EmailHandler} serves. */
@ApplicationScoped
public class EmailArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Communication";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "communicationServices", LocationPolicy.GLOBAL),
            ArmResourceType.resourceGroup(NAMESPACE, "emailServices", LocationPolicy.GLOBAL),
            ArmResourceType.child(NAMESPACE, "emailServices/domains"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
