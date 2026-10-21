package io.floci.az.services.aks;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.arm.NameRule;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/** The ARM resource types {@link AksHandler} serves. */
@ApplicationScoped
public class AksArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.ContainerService";

    /** The {@code resourceName} parameter of every managedClusters operation (2024-02-01). */
    static final NameRule CLUSTER_NAME = new NameRule(1, 63,
        Pattern.compile("[A-Za-z0-9]|[A-Za-z0-9][-_A-Za-z0-9]{0,61}[A-Za-z0-9]"), "InvalidResourceName",
        "The resource name '%s' is invalid: a cluster name is 1 to 63 letters, digits, underscores and hyphens, "
            + "starting and ending with a letter or digit.");

    /** The {@code agentPoolName} parameter (2024-02-01): Linux's 12-character bound, Windows pools allow 6. */
    static final NameRule AGENT_POOL_NAME = new NameRule(1, 12, Pattern.compile("[a-z][a-z0-9]{0,11}"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: an agent pool name is 1 to 12 lowercase letters and digits, starting "
            + "with a letter.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "managedClusters", LocationPolicy.REQUIRED)
                .withName(CLUSTER_NAME),
            ArmResourceType.child(NAMESPACE, "managedClusters/agentPools")
                .withName(AGENT_POOL_NAME))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
