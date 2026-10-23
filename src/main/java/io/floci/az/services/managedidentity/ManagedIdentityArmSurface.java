package io.floci.az.services.managedidentity;

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

/** The ARM resource types {@link ManagedIdentityHandler} serves. */
@ApplicationScoped
public class ManagedIdentityArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.ManagedIdentity";

    /**
     * {@code resource-name-rules.md}: 3 to 128 letters, digits, hyphens and underscores, starting with a letter or
     * digit. The spec declares no pattern for {@code resourceName} (2024-11-30).
     */
    static final NameRule IDENTITY_NAME = new NameRule(3, 128, Pattern.compile("[A-Za-z0-9][-_A-Za-z0-9]*"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: an identity name is 3 to 128 letters, digits, hyphens and underscores, "
            + "starting with a letter or digit.");

    /** The {@code federatedIdentityCredentialResourceName} parameter (2024-11-30). */
    static final NameRule CREDENTIAL_NAME = new NameRule(3, 120, Pattern.compile("[A-Za-z0-9][-_A-Za-z0-9]*"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: a federated identity credential name is 3 to 120 letters, digits, "
            + "hyphens and underscores, starting with a letter or digit.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            new ArmResourceType(NAMESPACE, "identities", ArmScopeKind.EXTENSION, LocationPolicy.OPTIONAL),
            ArmResourceType.resourceGroup(NAMESPACE, "userAssignedIdentities", LocationPolicy.REQUIRED)
                .withName(IDENTITY_NAME),
            ArmResourceType.child(NAMESPACE, "userAssignedIdentities/federatedIdentityCredentials")
                .withName(CREDENTIAL_NAME))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
