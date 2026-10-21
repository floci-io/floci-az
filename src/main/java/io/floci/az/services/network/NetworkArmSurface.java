package io.floci.az.services.network;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link NetworkHandler} serves. */
@ApplicationScoped
public class NetworkArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Network";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.ARM_HANDLER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "applicationGateways", LocationPolicy.REQUIRED),
            ArmResourceType.resourceGroup(NAMESPACE, "loadBalancers", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "loadBalancers/backendAddressPools"),
            ArmResourceType.resourceGroup(NAMESPACE, "networkInterfaces", LocationPolicy.REQUIRED),
            ArmResourceType.resourceGroup(NAMESPACE, "networkSecurityGroups", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "networkSecurityGroups/defaultSecurityRules"),
            ArmResourceType.child(NAMESPACE, "networkSecurityGroups/securityRules"),
            ArmResourceType.resourceGroup(NAMESPACE, "privateEndpoints", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "privateEndpoints/privateDnsZoneGroups"),
            ArmResourceType.resourceGroup(NAMESPACE, "privateLinkServices", LocationPolicy.REQUIRED),
            ArmResourceType.resourceGroup(NAMESPACE, "publicIPAddresses", LocationPolicy.REQUIRED),
            ArmResourceType.resourceGroup(NAMESPACE, "virtualNetworks", LocationPolicy.REQUIRED),
            ArmResourceType.child(NAMESPACE, "virtualNetworks/subnets"),
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
