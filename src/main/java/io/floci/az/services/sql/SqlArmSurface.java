package io.floci.az.services.sql;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link SqlHandler} serves. */
@ApplicationScoped
public class SqlArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Sql";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "servers", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "servers/connectionPolicies"),
            ArmResourceType.child(NAMESPACE, "servers/databases"),
            ArmResourceType.child(NAMESPACE, "servers/firewallRules"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
