package io.floci.az.services.monitor;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmScopeKind;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link MonitorHandler} serves. */
@ApplicationScoped
public class MonitorArmSurface implements ArmSurfaceProvider {

    private static final String INSIGHTS = "Microsoft.Insights";
    private static final String OPERATIONAL_INSIGHTS = "Microsoft.OperationalInsights";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(INSIGHTS, ArmLane.PROVIDER_SERVICE, List.of(
            ArmResourceType.resourceGroup(INSIGHTS, "dataCollectionEndpoints", LocationPolicy.REQUIRED),
            ArmResourceType.resourceGroup(INSIGHTS, "dataCollectionRules", LocationPolicy.REQUIRED),
            new ArmResourceType(INSIGHTS, "diagnosticSettings", ArmScopeKind.EXTENSION, LocationPolicy.OPTIONAL))),
        new ArmSurface(OPERATIONAL_INSIGHTS, ArmLane.PROVIDER_SERVICE, List.of(
            ArmResourceType.resourceGroup(OPERATIONAL_INSIGHTS, "workspaces", LocationPolicy.REQUIRED))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
