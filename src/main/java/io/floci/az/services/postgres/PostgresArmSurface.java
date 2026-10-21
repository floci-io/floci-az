package io.floci.az.services.postgres;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link PostgresHandler} serves. */
@ApplicationScoped
public class PostgresArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.DBforPostgreSQL";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "flexibleServers", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "flexibleServers/configurations"),
            ArmResourceType.child(NAMESPACE, "flexibleServers/databases"),
            ArmResourceType.child(NAMESPACE, "flexibleServers/firewallRules"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
