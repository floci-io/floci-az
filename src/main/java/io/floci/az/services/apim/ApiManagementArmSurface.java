package io.floci.az.services.apim;

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

/** The ARM resource types {@link ApiManagementHandler} serves. */
@ApplicationScoped
public class ApiManagementArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.ApiManagement";

    /**
     * 1 to 50 letters, digits and hyphens, starting with a letter and ending with a letter or digit: the
     * {@code serviceName} path parameter of every service operation (2022-08-01, which azurerm pins, and 2024-05-01).
     */
    static final NameRule SERVICE_NAME = new NameRule(1, 50, Pattern.compile("[a-zA-Z](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?"),
        "InvalidResourceName", "The resource name '%s' is invalid: an API Management service name is 1 to 50 letters, "
            + "digits and hyphens, starts with a letter and ends with a letter or digit.");

    /**
     * A service name is a global DNS label under {@code azure-api.net}: the conflict is the answer floci-az gave
     * before the name moved to the registry, whoever owns it.
     */
    static final GlobalName SERVICE_DNS_NAME = new GlobalName(CloudSuffix.APIM, 409, "ServiceAlreadyExists",
        "Api service already exists: %s");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.PROVIDER_SERVICE, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "service", LocationPolicy.REQUIRED)
                .withName(SERVICE_NAME)
                .withGlobalName(SERVICE_DNS_NAME),
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
