package io.floci.az.services.mariadb;

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

/** The ARM resource types {@link MariaDbHandler} serves. */
@ApplicationScoped
public class MariaDbArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.DBforMariaDB";

    /**
     * 3 to 63 lowercase letters, digits and hyphens, not starting or ending with a hyphen: {@code
     * resource-name-rules.md}, since the {@code serverName} parameter at 2018-06-01 declares no pattern or length.
     */
    static final NameRule SERVER_NAME = new NameRule(3, 63, Pattern.compile("[a-z0-9](?:[-a-z0-9]*[a-z0-9])?"),
        "InvalidResourceName", "The resource name '%s' is invalid: a server name is 3 to 63 lowercase letters, "
            + "digits and hyphens, and does not start or end with a hyphen.");

    /**
     * A server name is a global DNS label under {@code mariadb.database.azure.com}, and floci-az names its container
     * after it, so it is one namespace across clouds. The conflict is the answer the service has always given.
     */
    static final GlobalName SERVER_DNS_NAME = new GlobalName(CloudSuffix.MARIADB, 409, "ServerNameAlreadyExists",
        "Specified server name is already used.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "servers", LocationPolicy.REQUIRED)
                .withName(SERVER_NAME)
                .withGlobalName(SERVER_DNS_NAME),
            ArmResourceType.child(NAMESPACE, "servers/configurations"),
            ArmResourceType.child(NAMESPACE, "servers/databases"),
            ArmResourceType.child(NAMESPACE, "servers/firewallRules"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
