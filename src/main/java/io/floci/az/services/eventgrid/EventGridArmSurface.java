package io.floci.az.services.eventgrid;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmScopeKind;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link EventGridHandler} serves. */
@ApplicationScoped
public class EventGridArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.EventGrid";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.PROVIDER_SERVICE, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "topics", LocationPolicy.REQUIRED),
            new ArmResourceType(NAMESPACE, "eventSubscriptions", ArmScopeKind.EXTENSION, LocationPolicy.OPTIONAL))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
