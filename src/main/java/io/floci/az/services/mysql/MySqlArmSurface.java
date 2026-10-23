package io.floci.az.services.mysql;

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

/** The ARM resource types {@link MySqlHandler} serves. */
@ApplicationScoped
public class MySqlArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.DBforMySQL";

    /**
     * Lowercase letters, digits and hyphens, starting with a letter or digit and not ending with a hyphen: the
     * {@code serverName} pattern at 2023-12-30, the version azurerm pins, which declares no length. The 3 to 63
     * bound is {@code resource-name-rules.md}'s.
     */
    static final NameRule SERVER_NAME = new NameRule(3, 63, Pattern.compile("[a-z0-9][-a-z0-9]*(?<!-)"),
        "InvalidResourceName", "The resource name '%s' is invalid: a server name is 3 to 63 lowercase letters, "
            + "digits and hyphens, starting with a letter or digit and not ending with a hyphen.");

    /**
     * A server name is a global DNS label under {@code mysql.database.azure.com}, and floci-az names its container
     * after it, so it is one namespace across clouds. The conflict is the answer the service has always given.
     */
    static final GlobalName SERVER_DNS_NAME = new GlobalName(CloudSuffix.MYSQL, 409, "ServerNameAlreadyExists",
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
