package io.floci.az.services.postgres;

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

/** The ARM resource types {@link PostgresHandler} serves. */
@ApplicationScoped
public class PostgresArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.DBforPostgreSQL";

    /**
     * 3 to 63 letters, digits and single hyphens between them: the {@code serverName} parameter of the flexible
     * server operations (2024-08-01 and the 2025-08-01 azurerm pins agree).
     */
    static final NameRule SERVER_NAME = new NameRule(3, 63, Pattern.compile("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*"),
        "InvalidResourceName", "The resource name '%s' is invalid: a server name is 3 to 63 letters, digits and "
            + "hyphens, starting and ending with a letter or digit, and has no consecutive hyphens.");

    /**
     * A server name is a global DNS label under {@code postgres.database.azure.com}, and floci-az names its container
     * after it, so it is one namespace across clouds. The conflict is the answer the service has always given.
     */
    static final GlobalName SERVER_DNS_NAME = new GlobalName(CloudSuffix.POSTGRES, 409, "ServerNameAlreadyExists",
        "Specified server name is already used.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "flexibleServers", LocationPolicy.REQUIRED)
                .withName(SERVER_NAME)
                .withGlobalName(SERVER_DNS_NAME),
            ArmResourceType.child(NAMESPACE, "flexibleServers/configurations"),
            ArmResourceType.child(NAMESPACE, "flexibleServers/databases"),
            ArmResourceType.child(NAMESPACE, "flexibleServers/firewallRules"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
