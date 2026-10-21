package io.floci.az.services.redis;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link RedisHandler} serves. */
@ApplicationScoped
public class RedisArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Cache";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "redis", LocationPolicy.REQUIRED))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
