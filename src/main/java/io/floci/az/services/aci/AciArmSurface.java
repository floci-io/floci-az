package io.floci.az.services.aci;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.arm.NameRule;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/** The ARM resource types {@link AciHandler} serves. */
@ApplicationScoped
public class AciArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.ContainerInstance";

    /**
     * {@code resource-name-rules.md}: 1 to 63 lowercase letters, digits and hyphens, no leading, trailing or
     * consecutive hyphen. The spec declares no pattern for {@code containerGroupName}.
     */
    static final NameRule GROUP_NAME = new NameRule(1, 63, Pattern.compile("[a-z0-9](?:[a-z0-9]|-(?!-))*(?<!-)"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: a container group name is 1 to 63 lowercase letters, digits and "
            + "hyphens, does not start or end with a hyphen, and has no consecutive hyphens.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "containerGroups", LocationPolicy.REQUIRED)
                .withName(GROUP_NAME))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
