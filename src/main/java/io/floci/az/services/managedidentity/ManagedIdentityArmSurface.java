package io.floci.az.services.managedidentity;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmScopeKind;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link ManagedIdentityHandler} serves. */
@ApplicationScoped
public class ManagedIdentityArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.ManagedIdentity";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            new ArmResourceType(NAMESPACE, "identities", ArmScopeKind.EXTENSION, LocationPolicy.OPTIONAL),
            ArmResourceType.resourceGroup(NAMESPACE, "userAssignedIdentities", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "userAssignedIdentities/federatedIdentityCredentials"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
