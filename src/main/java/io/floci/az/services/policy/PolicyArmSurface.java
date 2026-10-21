package io.floci.az.services.policy;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmScopeKind;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link PolicyHandler} serves. */
@ApplicationScoped
public class PolicyArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Authorization";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            new ArmResourceType(NAMESPACE, "policyAssignments", ArmScopeKind.EXTENSION, LocationPolicy.OPTIONAL),
            new ArmResourceType(NAMESPACE, "policyDefinitions", ArmScopeKind.SUBSCRIPTION, LocationPolicy.OPTIONAL),
            new ArmResourceType(NAMESPACE, "policyExemptions", ArmScopeKind.EXTENSION, LocationPolicy.OPTIONAL),
            new ArmResourceType(NAMESPACE, "policySetDefinitions", ArmScopeKind.SUBSCRIPTION, LocationPolicy.OPTIONAL))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
