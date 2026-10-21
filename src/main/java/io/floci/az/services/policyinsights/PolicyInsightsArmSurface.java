package io.floci.az.services.policyinsights;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmScopeKind;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * The ARM resource type {@link PolicyInsightsHandler} serves: the tenant-level {@code policyMetadata}
 * listing. The compliance endpoints ({@code policyStates/...}) are actions on the provider, not resource types.
 */
@ApplicationScoped
public class PolicyInsightsArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.PolicyInsights";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            new ArmResourceType(NAMESPACE, "policyMetadata", ArmScopeKind.TENANT, LocationPolicy.NONE))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
