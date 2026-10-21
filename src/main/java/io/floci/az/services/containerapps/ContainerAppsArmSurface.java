package io.floci.az.services.containerapps;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.arm.NameRule;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/** The ARM resource types {@link ContainerAppsHandler} serves. */
@ApplicationScoped
public class ContainerAppsArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.App";

    /**
     * {@code resource-name-rules.md}: 2 to 32 lowercase letters, digits and hyphens, starting with a letter and
     * ending with a letter or digit. The spec declares no pattern for {@code containerAppName}, and neither source
     * gives one for managed environments, so they declare none.
     */
    static final NameRule APP_NAME = new NameRule(2, 32, Pattern.compile("[a-z][a-z0-9-]*[a-z0-9]"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: a container app name is 2 to 32 lowercase letters, digits and hyphens, "
            + "starting with a letter and ending with a letter or digit.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "containerApps", LocationPolicy.REQUIRED)
                .withName(APP_NAME),
            ArmResourceType.child(NAMESPACE, "containerApps/revisions"),
            ArmResourceType.resourceGroup(NAMESPACE, "managedEnvironments", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "managedEnvironments/storages"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
