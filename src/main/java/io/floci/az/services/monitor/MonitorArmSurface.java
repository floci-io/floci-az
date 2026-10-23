package io.floci.az.services.monitor;

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

/** The ARM resource types {@link MonitorHandler} serves. */
@ApplicationScoped
public class MonitorArmSurface implements ArmSurfaceProvider {

    private static final String INSIGHTS = "Microsoft.Insights";
    private static final String OPERATIONAL_INSIGHTS = "Microsoft.OperationalInsights";

    /**
     * The {@code workspaceName} parameter of {@code Workspaces_CreateOrUpdate} (the same at 2022-10-01, 2023-09-01
     * and 2025-07-01): 4 to 63 letters, digits and hyphens, starting and ending with a letter or digit, as
     * {@code resource-name-rules.md} says too. Neither source gives a rule for data collection endpoints, rules or
     * diagnostic settings, so they declare none.
     */
    static final NameRule WORKSPACE_NAME = new NameRule(4, 63, Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]+[A-Za-z0-9]"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: a workspace name is 4 to 63 letters, digits and hyphens, starting and "
            + "ending with a letter or digit.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(INSIGHTS, ArmLane.PROVIDER_SERVICE, List.of(
            ArmResourceType.resourceGroup(INSIGHTS, "dataCollectionEndpoints", LocationPolicy.REQUIRED),
            ArmResourceType.resourceGroup(INSIGHTS, "dataCollectionRules", LocationPolicy.REQUIRED),
            new ArmResourceType(INSIGHTS, "diagnosticSettings", ArmScopeKind.EXTENSION, LocationPolicy.OPTIONAL))),
        new ArmSurface(OPERATIONAL_INSIGHTS, ArmLane.PROVIDER_SERVICE, List.of(
            ArmResourceType.resourceGroup(OPERATIONAL_INSIGHTS, "workspaces", LocationPolicy.REQUIRED)
                .withName(WORKSPACE_NAME))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
