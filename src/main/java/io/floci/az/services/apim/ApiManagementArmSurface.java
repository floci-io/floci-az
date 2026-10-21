package io.floci.az.services.apim;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link ApiManagementHandler} serves. */
@ApplicationScoped
public class ApiManagementArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.ApiManagement";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.PROVIDER_SERVICE, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "service", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "service/apis"),
            ArmResourceType.child(NAMESPACE, "service/apis/operations"),
            ArmResourceType.child(NAMESPACE, "service/apis/operations/policies"),
            ArmResourceType.child(NAMESPACE, "service/apis/policies"),
            ArmResourceType.child(NAMESPACE, "service/backends"),
            ArmResourceType.child(NAMESPACE, "service/namedValues"),
            ArmResourceType.child(NAMESPACE, "service/policies"),
            ArmResourceType.child(NAMESPACE, "service/products"),
            ArmResourceType.child(NAMESPACE, "service/products/apis"),
            ArmResourceType.child(NAMESPACE, "service/subscriptions"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
