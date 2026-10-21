package io.floci.az.services.acr;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.GlobalName;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.arm.NameRule;
import io.floci.az.core.cloud.CloudSuffix;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/** The ARM resource types {@link AcrHandler} serves. */
@ApplicationScoped
public class AcrArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.ContainerRegistry";

    /** 5 to 50 alphanumerics, the {@code registryName} parameter of every registries operation (2023-07-01). */
    static final NameRule REGISTRY_NAME = new NameRule(5, 50, Pattern.compile("[A-Za-z0-9]+"), "InvalidResourceName",
        "The resource name '%s' is invalid: a registry name is 5 to 50 letters and digits.");

    /** A registry name is a global DNS label under {@code azurecr.io}; the conflict is Azure's own text. */
    static final GlobalName REGISTRY_DNS_NAME = new GlobalName(CloudSuffix.ACR, 409, "AlreadyInUse",
        "The registry DNS name %s.azurecr.io is already in use. You can check if the name is already claimed using "
            + "following API: https://docs.microsoft.com/en-us/rest/api/containerregistry/registries/checknameavailability");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "registries", LocationPolicy.REQUIRED)
                .withName(REGISTRY_NAME)
                .withGlobalName(REGISTRY_DNS_NAME),
            ArmResourceType.child(NAMESPACE, "registries/replications"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
