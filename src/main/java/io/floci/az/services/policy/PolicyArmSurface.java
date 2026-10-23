package io.floci.az.services.policy;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmScopeKind;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.arm.NameRule;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/** The ARM resource types {@link PolicyHandler} serves. */
@ApplicationScoped
public class PolicyArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Authorization";

    /**
     * A policy definition, set definition, assignment or exemption name: none of {@code <>%&:\?/}, not ending with a
     * space (the {@code policy*Name} path parameter pattern from 2025-01-01, TypeSpec {@code NamePattern}), and 1 to
     * 64 characters ({@code resource-name-rules.md}; the spec declares no length). The docs' stricter points are not
     * checked: {@code #} and control characters, a trailing period, and an assignment's 24-character bound at
     * management-group scope.
     */
    static final NameRule POLICY_NAME = new NameRule(1, 64, Pattern.compile("[^<>%&:\\\\?/]*[^<>%&:\\\\?/ ]+"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: a policy resource name is 1 to 64 characters, none of <>%%&:\\?/, "
            + "and does not end with a space.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            new ArmResourceType(NAMESPACE, "policyAssignments", ArmScopeKind.EXTENSION, LocationPolicy.OPTIONAL)
                .withName(POLICY_NAME),
            new ArmResourceType(NAMESPACE, "policyDefinitions", ArmScopeKind.SUBSCRIPTION, LocationPolicy.NONE)
                .withName(POLICY_NAME),
            new ArmResourceType(NAMESPACE, "policyExemptions", ArmScopeKind.EXTENSION, LocationPolicy.OPTIONAL)
                .withName(POLICY_NAME),
            new ArmResourceType(NAMESPACE, "policySetDefinitions", ArmScopeKind.SUBSCRIPTION, LocationPolicy.NONE)
                .withName(POLICY_NAME))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
