package io.floci.az.services.sql;

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

/** The ARM resource types {@link SqlHandler} serves. */
@ApplicationScoped
public class SqlArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Sql";

    /**
     * 1 to 63 lowercase letters, digits and hyphens, not starting or ending with a hyphen
     * ({@code resource-name-rules.md}, and azurerm's {@code ValidateMsSqlServerName}); the spec's {@code serverName}
     * parameter declares no pattern.
     */
    static final NameRule SERVER_NAME = new NameRule(1, 63, Pattern.compile("[a-z0-9](?:[-a-z0-9]*[a-z0-9])?"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: a server name is 1 to 63 lowercase letters, digits and hyphens, and "
            + "does not start or end with a hyphen.");

    /**
     * 1 to 128 characters without {@code <>*%&:\/?} or control characters, not ending with a period or a space
     * ({@code resource-name-rules.md}, and azurerm's {@code ValidateMsSqlDatabaseName}).
     */
    static final NameRule DATABASE_NAME = new NameRule(1, 128,
        Pattern.compile("[^<>*%&:\\\\/?\\p{Cntrl}]*[^<>*%&:\\\\/?\\p{Cntrl}. ]"), "InvalidResourceName",
        "The resource name '%s' is invalid: a database name is 1 to 128 characters without <>*%%&:\\/? or control "
            + "characters, and does not end with a period or a space.");

    /**
     * 1 to 128 characters without {@code <>*%&:;\/?} or control characters, not ending with a period
     * ({@code resource-name-rules.md}).
     */
    static final NameRule FIREWALL_RULE_NAME = new NameRule(1, 128,
        Pattern.compile("[^<>*%&:;\\\\/?\\p{Cntrl}]*[^<>*%&:;\\\\/?\\p{Cntrl}.]"), "InvalidResourceName",
        "The resource name '%s' is invalid: a firewall rule name is 1 to 128 characters without <>*%%&:;\\/? or "
            + "control characters, and does not end with a period.");

    /** A server name is a global DNS name under {@code database.windows.net}; the conflict Azure SQL answers. */
    static final GlobalName SERVER_DNS_NAME = new GlobalName(CloudSuffix.SQL, 400, "NameAlreadyExists",
        "The name '%s' already exists. Choose a different name.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "servers", LocationPolicy.REQUIRED)
                .withName(SERVER_NAME)
                .withGlobalName(SERVER_DNS_NAME),
            ArmResourceType.child(NAMESPACE, "servers/connectionPolicies"),
            ArmResourceType.child(NAMESPACE, "servers/databases")
                .withName(DATABASE_NAME),
            ArmResourceType.child(NAMESPACE, "servers/firewallRules")
                .withName(FIREWALL_RULE_NAME))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
