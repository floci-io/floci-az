package io.floci.az.services.redis;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.GlobalName;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.arm.NameRule;
import io.floci.az.core.cloud.CloudSuffix;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link RedisHandler} serves. */
@ApplicationScoped
public class RedisArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Cache";

    /** A cache name is a global DNS label under {@code redis.cache.windows.net}; the conflict is Azure's own text. */
    static final GlobalName CACHE_NAME = new GlobalName(CloudSuffix.REDIS, 409, "NameNotAvailable",
        "An error occured when trying to reserve the DNS name for the cache instance. This may be a temporary issue "
            + "if a cache instance of this name was recently deleted. Please choose a different name or try again "
            + "later.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "redis", LocationPolicy.REQUIRED)
                .withName(NameRule.REDIS)
                .withGlobalName(CACHE_NAME))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
