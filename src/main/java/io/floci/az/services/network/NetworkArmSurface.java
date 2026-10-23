package io.floci.az.services.network;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.arm.NameRule;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/** The ARM resource types {@link NetworkHandler} serves. */
@ApplicationScoped
public class NetworkArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Network";

    /**
     * {@code resource-name-rules.md} (Microsoft.Network) gives most Network types one shape: letters, digits,
     * underscores, periods and hyphens, starting with a letter or digit and ending with a letter, digit or
     * underscore. Only the length differs. The Network spec declares no path parameter pattern.
     */
    private static final Pattern NETWORK_NAME = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9_.-]*[A-Za-z0-9_])?");

    /** {@code virtualNetworks}: 2 to 64 characters. */
    static final NameRule VIRTUAL_NETWORK = networkName("virtual network", 2, 64);

    /** {@code virtualnetworks / subnets}: 1 to 80 characters. */
    static final NameRule SUBNET = networkName("subnet", 1, 80);

    /** {@code networkInterfaces}: 1 to 80 characters. */
    static final NameRule NETWORK_INTERFACE = networkName("network interface", 1, 80);

    /** {@code publicIPAddresses}: 1 to 80 characters. */
    static final NameRule PUBLIC_IP_ADDRESS = networkName("public IP address", 1, 80);

    /** {@code networkSecurityGroups}: 1 to 80 characters. */
    static final NameRule NETWORK_SECURITY_GROUP = networkName("network security group", 1, 80);

    private static NameRule networkName(String kind, int min, int max) {
        return new NameRule(min, max, NETWORK_NAME, "InvalidResourceName",
            "The resource name '%s' is invalid: a " + kind + " name is " + min + " to " + max + " letters, digits, "
                + "underscores, periods and hyphens, starts with a letter or digit, and ends with a letter, digit or "
                + "underscore.");
    }

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.ARM_HANDLER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "applicationGateways", LocationPolicy.REQUIRED),
            ArmResourceType.resourceGroup(NAMESPACE, "loadBalancers", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "loadBalancers/backendAddressPools"),
            ArmResourceType.resourceGroup(NAMESPACE, "networkInterfaces", LocationPolicy.REQUIRED)
                .withName(NETWORK_INTERFACE),
            ArmResourceType.resourceGroup(NAMESPACE, "networkSecurityGroups", LocationPolicy.REQUIRED)
                .withName(NETWORK_SECURITY_GROUP),
            ArmResourceType.child(NAMESPACE, "networkSecurityGroups/defaultSecurityRules"),
            ArmResourceType.child(NAMESPACE, "networkSecurityGroups/securityRules"),
            ArmResourceType.resourceGroup(NAMESPACE, "privateEndpoints", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "privateEndpoints/privateDnsZoneGroups"),
            ArmResourceType.resourceGroup(NAMESPACE, "privateLinkServices", LocationPolicy.REQUIRED),
            ArmResourceType.resourceGroup(NAMESPACE, "publicIPAddresses", LocationPolicy.REQUIRED)
                .withName(PUBLIC_IP_ADDRESS),
            ArmResourceType.resourceGroup(NAMESPACE, "virtualNetworks", LocationPolicy.REQUIRED)
                .withName(VIRTUAL_NETWORK),
            ArmResourceType.child(NAMESPACE, "virtualNetworks/subnets")
                .withName(SUBNET),
            ArmResourceType.resourceGroup(NAMESPACE, "dnsZones", LocationPolicy.GLOBAL),
            ArmResourceType.child(NAMESPACE, "dnsZones/A"),
            ArmResourceType.child(NAMESPACE, "dnsZones/AAAA"),
            ArmResourceType.child(NAMESPACE, "dnsZones/CAA"),
            ArmResourceType.child(NAMESPACE, "dnsZones/CNAME"),
            ArmResourceType.child(NAMESPACE, "dnsZones/MX"),
            ArmResourceType.child(NAMESPACE, "dnsZones/NS"),
            ArmResourceType.child(NAMESPACE, "dnsZones/SOA"),
            ArmResourceType.child(NAMESPACE, "dnsZones/SRV"),
            ArmResourceType.child(NAMESPACE, "dnsZones/TXT"),
            ArmResourceType.resourceGroup(NAMESPACE, "privateDnsZones", LocationPolicy.GLOBAL),
            ArmResourceType.resourceGroup(NAMESPACE, "privateDnsZones/virtualNetworkLinks", LocationPolicy.GLOBAL),
            ArmResourceType.child(NAMESPACE, "privateDnsZones/A"),
            ArmResourceType.child(NAMESPACE, "privateDnsZones/AAAA"),
            ArmResourceType.child(NAMESPACE, "privateDnsZones/CNAME"),
            ArmResourceType.child(NAMESPACE, "privateDnsZones/MX"),
            ArmResourceType.child(NAMESPACE, "privateDnsZones/PTR"),
            ArmResourceType.child(NAMESPACE, "privateDnsZones/SOA"),
            ArmResourceType.child(NAMESPACE, "privateDnsZones/SRV"),
            ArmResourceType.child(NAMESPACE, "privateDnsZones/TXT"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
