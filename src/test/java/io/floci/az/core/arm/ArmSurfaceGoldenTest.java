package io.floci.az.core.arm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every ARM resource type floci-az declares, with its scope, location policy and lane, as a literal table, so a
 * descriptor change shows up in review as a change here. Add a row with a new type; never regenerate the table
 * to make a failure go away without reading what moved.
 */
@QuarkusTest
class ArmSurfaceGoldenTest {

    private static final String GOLDEN = """
        Microsoft.ApiManagement/service                                                RESOURCE_GROUP  REQUIRED  PROVIDER_SERVICE
        Microsoft.ApiManagement/service/apis                                           RESOURCE_GROUP  OPTIONAL  PROVIDER_SERVICE
        Microsoft.ApiManagement/service/apis/operations                                RESOURCE_GROUP  OPTIONAL  PROVIDER_SERVICE
        Microsoft.ApiManagement/service/apis/operations/policies                       RESOURCE_GROUP  OPTIONAL  PROVIDER_SERVICE
        Microsoft.ApiManagement/service/apis/policies                                  RESOURCE_GROUP  OPTIONAL  PROVIDER_SERVICE
        Microsoft.ApiManagement/service/backends                                       RESOURCE_GROUP  OPTIONAL  PROVIDER_SERVICE
        Microsoft.ApiManagement/service/namedValues                                    RESOURCE_GROUP  OPTIONAL  PROVIDER_SERVICE
        Microsoft.ApiManagement/service/policies                                       RESOURCE_GROUP  OPTIONAL  PROVIDER_SERVICE
        Microsoft.ApiManagement/service/products                                       RESOURCE_GROUP  OPTIONAL  PROVIDER_SERVICE
        Microsoft.ApiManagement/service/products/apis                                  RESOURCE_GROUP  OPTIONAL  PROVIDER_SERVICE
        Microsoft.ApiManagement/service/subscriptions                                  RESOURCE_GROUP  OPTIONAL  PROVIDER_SERVICE
        Microsoft.App/containerApps                                                    RESOURCE_GROUP  REQUIRED  FILTER
        Microsoft.App/containerApps/revisions                                          RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.App/managedEnvironments                                              RESOURCE_GROUP  REQUIRED  FILTER
        Microsoft.App/managedEnvironments/storages                                     RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.Authorization/policyAssignments                                      EXTENSION       OPTIONAL  FILTER
        Microsoft.Authorization/policyDefinitions                                      SUBSCRIPTION    NONE      FILTER
        Microsoft.Authorization/policyExemptions                                       EXTENSION       OPTIONAL  FILTER
        Microsoft.Authorization/policySetDefinitions                                   SUBSCRIPTION    NONE      FILTER
        Microsoft.Cache/redis                                                          RESOURCE_GROUP  REQUIRED  FILTER
        Microsoft.Communication/communicationServices                                  RESOURCE_GROUP  GLOBAL    FILTER
        Microsoft.Communication/emailServices                                          RESOURCE_GROUP  GLOBAL    FILTER
        Microsoft.Communication/emailServices/domains                                  RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.Compute/virtualMachines                                              RESOURCE_GROUP  REQUIRED  FILTER
        Microsoft.ContainerInstance/containerGroups                                    RESOURCE_GROUP  REQUIRED  FILTER
        Microsoft.ContainerRegistry/registries                                         RESOURCE_GROUP  REQUIRED  FILTER
        Microsoft.ContainerRegistry/registries/replications                            RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.ContainerService/managedClusters                                     RESOURCE_GROUP  REQUIRED  FILTER
        Microsoft.ContainerService/managedClusters/agentPools                          RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.DBforMariaDB/servers                                                 RESOURCE_GROUP  REQUIRED  FILTER
        Microsoft.DBforMariaDB/servers/configurations                                  RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.DBforMariaDB/servers/databases                                       RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.DBforMariaDB/servers/firewallRules                                   RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.DBforMySQL/flexibleServers                                           RESOURCE_GROUP  REQUIRED  FILTER
        Microsoft.DBforMySQL/flexibleServers/configurations                            RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.DBforMySQL/flexibleServers/databases                                 RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.DBforMySQL/flexibleServers/firewallRules                             RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.DBforPostgreSQL/flexibleServers                                      RESOURCE_GROUP  REQUIRED  FILTER
        Microsoft.DBforPostgreSQL/flexibleServers/configurations                       RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.DBforPostgreSQL/flexibleServers/databases                            RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.DBforPostgreSQL/flexibleServers/firewallRules                        RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.EventGrid/eventSubscriptions                                         EXTENSION       OPTIONAL  PROVIDER_SERVICE
        Microsoft.EventGrid/topics                                                     RESOURCE_GROUP  REQUIRED  PROVIDER_SERVICE
        Microsoft.Insights/dataCollectionEndpoints                                     RESOURCE_GROUP  REQUIRED  PROVIDER_SERVICE
        Microsoft.Insights/dataCollectionRules                                         RESOURCE_GROUP  REQUIRED  PROVIDER_SERVICE
        Microsoft.Insights/diagnosticSettings                                          EXTENSION       OPTIONAL  PROVIDER_SERVICE
        Microsoft.KeyVault/managedHSMs                                                 RESOURCE_GROUP  REQUIRED  ARM_HANDLER
        Microsoft.KeyVault/vaults                                                      RESOURCE_GROUP  REQUIRED  ARM_HANDLER
        Microsoft.ManagedIdentity/identities                                           EXTENSION       OPTIONAL  FILTER
        Microsoft.ManagedIdentity/userAssignedIdentities                               RESOURCE_GROUP  REQUIRED  FILTER
        Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials  RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.Network/applicationGateways                                          RESOURCE_GROUP  REQUIRED  ARM_HANDLER
        Microsoft.Network/dnsZones                                                     RESOURCE_GROUP  GLOBAL    ARM_HANDLER
        Microsoft.Network/dnsZones/A                                                   RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/dnsZones/AAAA                                                RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/dnsZones/CAA                                                 RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/dnsZones/CNAME                                               RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/dnsZones/MX                                                  RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/dnsZones/NS                                                  RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/dnsZones/SOA                                                 RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/dnsZones/SRV                                                 RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/dnsZones/TXT                                                 RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/loadBalancers                                                RESOURCE_GROUP  REQUIRED  ARM_HANDLER
        Microsoft.Network/loadBalancers/backendAddressPools                            RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/networkInterfaces                                            RESOURCE_GROUP  REQUIRED  ARM_HANDLER
        Microsoft.Network/networkSecurityGroups                                        RESOURCE_GROUP  REQUIRED  ARM_HANDLER
        Microsoft.Network/networkSecurityGroups/defaultSecurityRules                   RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/networkSecurityGroups/securityRules                          RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/privateDnsZones                                              RESOURCE_GROUP  GLOBAL    ARM_HANDLER
        Microsoft.Network/privateDnsZones/A                                            RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/privateDnsZones/AAAA                                         RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/privateDnsZones/CNAME                                        RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/privateDnsZones/MX                                           RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/privateDnsZones/PTR                                          RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/privateDnsZones/SOA                                          RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/privateDnsZones/SRV                                          RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/privateDnsZones/TXT                                          RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/privateDnsZones/virtualNetworkLinks                          RESOURCE_GROUP  GLOBAL    ARM_HANDLER
        Microsoft.Network/privateEndpoints                                             RESOURCE_GROUP  REQUIRED  ARM_HANDLER
        Microsoft.Network/privateEndpoints/privateDnsZoneGroups                        RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Network/privateLinkServices                                          RESOURCE_GROUP  REQUIRED  ARM_HANDLER
        Microsoft.Network/publicIPAddresses                                            RESOURCE_GROUP  REQUIRED  ARM_HANDLER
        Microsoft.Network/virtualNetworks                                              RESOURCE_GROUP  REQUIRED  ARM_HANDLER
        Microsoft.Network/virtualNetworks/subnets                                      RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.OperationalInsights/workspaces                                       RESOURCE_GROUP  REQUIRED  PROVIDER_SERVICE
        Microsoft.ResourceGraph/queries                                                RESOURCE_GROUP  GLOBAL    FILTER
        Microsoft.Resources/resourceGroups                                             SUBSCRIPTION    REQUIRED  ARM_HANDLER
        Microsoft.Sql/servers                                                          RESOURCE_GROUP  REQUIRED  FILTER
        Microsoft.Sql/servers/connectionPolicies                                       RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.Sql/servers/databases                                                RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.Sql/servers/firewallRules                                            RESOURCE_GROUP  OPTIONAL  FILTER
        Microsoft.Storage/storageAccounts                                              RESOURCE_GROUP  REQUIRED  ARM_HANDLER
        Microsoft.Storage/storageAccounts/blobServices                                 RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Storage/storageAccounts/blobServices/containers                      RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Storage/storageAccounts/queueServices                                RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Storage/storageAccounts/queueServices/queues                         RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        Microsoft.Web/sites                                                            RESOURCE_GROUP  REQUIRED  ARM_HANDLER
        Microsoft.Web/sites/config                                                     RESOURCE_GROUP  OPTIONAL  ARM_HANDLER
        """;

    /** The types that declare a name rule or a global DNS name: their bounds, suffix and conflict answer. */
    private static final String NAMED = """
        Microsoft.ApiManagement/service    1-50    APIM    409    ServiceAlreadyExists
        Microsoft.App/containerApps    2-32    null    0    null
        Microsoft.Cache/redis    1-63    REDIS    409    NameNotAvailable
        Microsoft.Communication/communicationServices    1-63     COMMUNICATION    409    Conflict
        Microsoft.Communication/emailServices            1-63     null    0    null
        Microsoft.Communication/emailServices/domains    1-253    null    0    null
        Microsoft.Compute/virtualMachines    1-64    null    0    null
        Microsoft.ContainerInstance/containerGroups    1-63    null    0    null
        Microsoft.ContainerRegistry/registries    5-50    ACR    409    AlreadyInUse
        Microsoft.ContainerService/managedClusters               1-63    null    0    null
        Microsoft.ContainerService/managedClusters/agentPools    1-12    null    0    null
        Microsoft.DBforMariaDB/servers    3-63    MARIADB    409    ServerNameAlreadyExists
        Microsoft.DBforMySQL/flexibleServers    3-63    MYSQL    409    ServerNameAlreadyExists
        Microsoft.DBforPostgreSQL/flexibleServers    3-63    POSTGRES    409    ServerNameAlreadyExists
        Microsoft.EventGrid/eventSubscriptions    3-64    null    0    null
        Microsoft.EventGrid/topics    3-50    null    0    null
        Microsoft.Sql/servers                  1-63     SQL     400    NameAlreadyExists
        Microsoft.Sql/servers/databases        1-128    null    0      null
        Microsoft.Sql/servers/firewallRules    1-128    null    0      null
        """;

    @Inject
    ArmTypeRegistry registry;

    @Test
    void theDeclaredTypesMatchTheGoldenTable() {
        List<String> golden = GOLDEN.lines().map(String::strip).filter(line -> !line.isEmpty())
            .map(line -> line.replaceAll("\\s+", " ")).toList();
        List<String> actual = registry.surfaces().stream()
            .flatMap(surface -> surface.types().stream()
                .map(type -> type.type() + " " + type.scope() + " " + type.location() + " " + surface.lane()))
            .sorted(Comparator.comparing(String::toLowerCase))
            .collect(Collectors.toList());
        assertEquals(String.join("\n", golden), String.join("\n", actual));
    }

    @Test
    void theNamedTypesMatchTheGoldenTable() {
        List<String> golden = NAMED.lines().map(String::strip).filter(line -> !line.isEmpty())
            .map(line -> line.replaceAll("\\s+", " ")).toList();
        List<String> actual = registry.surfaces().stream()
            .flatMap(surface -> surface.types().stream())
            .filter(type -> type.name() != NameRule.NONE || type.globalName().declared())
            .map(type -> type.type() + " " + type.name().minLength() + "-" + type.name().maxLength() + " "
                + type.globalName().suffix() + " " + type.globalName().conflictStatus() + " "
                + type.globalName().conflictCode())
            .sorted(Comparator.comparing(String::toLowerCase))
            .collect(Collectors.toList());
        assertEquals(String.join("\n", golden), String.join("\n", actual));
    }
}
