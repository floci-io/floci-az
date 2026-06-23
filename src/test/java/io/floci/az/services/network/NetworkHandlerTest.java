package io.floci.az.services.network;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@DisplayName("NetworkHandler - ARM Virtual Network compatibility")
class NetworkHandlerTest {

    private static final String SUB = "test-sub-net";
    private static final String RG = "test-rg-net";
    private static final String API = "?api-version=2024-05-01";
    private static final String BASE =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.Network";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void virtualNetworkLifecycleAndSubnetListing() {
        String vnetBody = """
                {
                  "location": "eastus",
                  "tags": {"env": "test"},
                  "properties": {
                    "addressSpace": {
                      "addressPrefixes": ["10.10.0.0/16"]
                    }
                  }
                }
                """;

        given().contentType("application/json").body(vnetBody)
                .when().put(BASE + "/virtualNetworks/vnet1" + API)
                .then().statusCode(200)
                .body("name", equalTo("vnet1"))
                .body("type", equalTo("Microsoft.Network/virtualNetworks"))
                .body("location", equalTo("eastus"))
                .body("tags.env", equalTo("test"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.addressSpace.addressPrefixes", hasItem("10.10.0.0/16"));

        given().when().get(BASE + "/virtualNetworks" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("vnet1"));

        String subnetBody = """
                {
                  "properties": {
                    "addressPrefix": "10.10.1.0/24"
                  }
                }
                """;

        given().contentType("application/json").body(subnetBody)
                .when().put(BASE + "/virtualNetworks/vnet1/subnets/default" + API)
                .then().statusCode(200)
                .body("name", equalTo("default"))
                .body("type", equalTo("Microsoft.Network/virtualNetworks/subnets"))
                .body("properties.addressPrefix", equalTo("10.10.1.0/24"))
                .body("properties.provisioningState", equalTo("Succeeded"));

        given().when().get(BASE + "/virtualNetworks/vnet1/subnets" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("default"));

        given().when().delete(BASE + "/virtualNetworks/vnet1" + API)
                .then().statusCode(200);

        given().when().get(BASE + "/virtualNetworks/vnet1" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void networkInterfaceSynthesizesPrivateIpForVmCompatibility() {
        String nicBody = """
                {
                  "location": "eastus",
                  "properties": {
                    "ipConfigurations": [
                      {
                        "name": "ipconfig1",
                        "properties": {
                          "subnet": {
                            "id": "/subscriptions/test-sub-net/resourceGroups/test-rg-net/providers/Microsoft.Network/virtualNetworks/vnet1/subnets/default"
                          }
                        }
                      }
                    ]
                  }
                }
                """;

        given().contentType("application/json").body(nicBody)
                .when().put(BASE + "/networkInterfaces/nic1" + API)
                .then().statusCode(200)
                .body("name", equalTo("nic1"))
                .body("type", equalTo("Microsoft.Network/networkInterfaces"))
                .body("properties.ipConfigurations[0].properties.privateIPAddress", equalTo("10.0.0.4"))
                .body("properties.ipConfigurations[0].properties.privateIPAllocationMethod", equalTo("Dynamic"))
                .body("properties.ipConfigurations[0].properties.primary", equalTo(true))
                .body("properties.ipConfigurations[0].properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    void dnsZoneAndRecordSetsLifecycleAndConcurrency() {
        String zoneBody = """
                {
                  "location": "global",
                  "tags": {"project": "floci"},
                  "properties": {
                    "zoneType": "Public"
                  }
                }
                """;

        // 1. Create DNS Zone
        given().contentType("application/json").body(zoneBody)
                .when().put(BASE + "/dnsZones/example.com" + API)
                .then().statusCode(201)
                .body("name", equalTo("example.com"))
                .body("type", equalTo("Microsoft.Network/dnsZones"))
                .body("location", equalTo("global"))
                .body("tags.project", equalTo("floci"))
                .body("properties.zoneType", equalTo("Public"))
                .body("properties.numberOfRecordSets", equalTo(2))
                .body("properties.nameServers", hasSize(4));

        // 2. Get default SOA and NS records
        given().when().get(BASE + "/dnsZones/example.com/SOA/@" + API)
                .then().statusCode(200)
                .body("name", equalTo("@"))
                .body("type", equalTo("Microsoft.Network/dnsZones/SOA"))
                .body("properties.fqdn", equalTo("example.com."))
                .body("properties.SOARecord.host", equalTo("ns1-01.azure-dns.com."));

        given().when().get(BASE + "/dnsZones/example.com/NS/@" + API)
                .then().statusCode(200)
                .body("name", equalTo("@"))
                .body("type", equalTo("Microsoft.Network/dnsZones/NS"))
                .body("properties.fqdn", equalTo("example.com."))
                .body("properties.NSRecords[0].nsdname", equalTo("ns1-01.azure-dns.com."));

        // 3. Create A Record Set
        String aRecordBody = """
                {
                  "properties": {
                    "TTL": 600,
                    "ARecords": [
                      {"ipv4Address": "192.168.1.1"}
                    ]
                  }
                }
                """;

        String etag = given().contentType("application/json").body(aRecordBody)
                .when().put(BASE + "/dnsZones/example.com/A/www" + API)
                .then().statusCode(201)
                .body("name", equalTo("www"))
                .body("type", equalTo("Microsoft.Network/dnsZones/A"))
                .body("properties.fqdn", equalTo("www.example.com."))
                .body("properties.TTL", equalTo(600))
                .body("properties.ARecords[0].ipv4Address", equalTo("192.168.1.1"))
                .extract().path("etag");

        // 4. Verify parent zone record count incremented to 3
        given().when().get(BASE + "/dnsZones/example.com" + API)
                .then().statusCode(200)
                .body("properties.numberOfRecordSets", equalTo(3));

        // 5. Test concurrency checks
        // PUT with wrong If-Match should fail
        given().contentType("application/json").body(aRecordBody)
                .header("If-Match", "\"wrong-etag\"")
                .when().put(BASE + "/dnsZones/example.com/A/www" + API)
                .then().statusCode(412);

        // PUT with If-None-Match: * should fail
        given().contentType("application/json").body(aRecordBody)
                .header("If-None-Match", "*")
                .when().put(BASE + "/dnsZones/example.com/A/www" + API)
                .then().statusCode(412);

        // PUT with correct If-Match should succeed
        String newEtag = given().contentType("application/json")
                .body("""
                      {
                        "properties": {
                          "TTL": 300,
                          "aRecords": [{"ipv4Address": "10.0.0.1"}]
                        }
                      }
                      """)
                .header("If-Match", etag)
                .when().put(BASE + "/dnsZones/example.com/A/www" + API)
                .then().statusCode(200)
                .body("properties.TTL", equalTo(300))
                .body("properties.ARecords[0].ipv4Address", equalTo("10.0.0.1"))
                .extract().path("etag");

        // 6. Create CNAME and TXT records
        given().contentType("application/json")
                .body("""
                      {
                        "properties": {
                          "TTL": 3600,
                          "cnameRecord": {"cname": "www.example.com"}
                        }
                      }
                      """)
                .when().put(BASE + "/dnsZones/example.com/CNAME/alias" + API)
                .then().statusCode(201);

        given().contentType("application/json")
                .body("""
                      {
                        "properties": {
                          "TTL": 3600,
                          "txtRecords": [{"value": ["v=spf1 include:spf.protection.outlook.com -all"]}]
                        }
                      }
                      """)
                .when().put(BASE + "/dnsZones/example.com/TXT/@" + API)
                .then().statusCode(201);

        // 7. List all record sets
        given().when().get(BASE + "/dnsZones/example.com/recordsets" + API)
                .then().statusCode(200)
                .body("value", hasSize(5)); // SOA@, NS@, A/www, CNAME/alias, TXT@

        // 8. List record sets by type
        given().when().get(BASE + "/dnsZones/example.com/A" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("www"));

        // 9. Try to delete default root NS record (should fail)
        given().when().delete(BASE + "/dnsZones/example.com/NS/@" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadRequest"));

        // 10. Delete custom A record
        given().when().delete(BASE + "/dnsZones/example.com/A/www" + API)
                .then().statusCode(200);

        // Parent zone count decremented back to 4 (NS@, SOA@, CNAME/alias, TXT@)
        given().when().get(BASE + "/dnsZones/example.com" + API)
                .then().statusCode(200)
                .body("properties.numberOfRecordSets", equalTo(4));

        // 11. Delete DNS Zone
        given().when().delete(BASE + "/dnsZones/example.com" + API)
                .then().statusCode(200);

        // Zone not found
        given().when().get(BASE + "/dnsZones/example.com" + API)
                .then().statusCode(404);

        // Records also deleted
        given().when().get(BASE + "/dnsZones/example.com/CNAME/alias" + API)
                .then().statusCode(404);
    }

    @Test
    void privateDnsZoneAndRecordSetsLifecycle() {
        String zoneBody = """
                {
                  "location": "global",
                  "tags": {"project": "floci"}
                }
                """;

        // 1. Create Private DNS Zone
        given().contentType("application/json").body(zoneBody)
                .when().put(BASE + "/privateDnsZones/privatelink.blob.core.windows.net" + API)
                .then().statusCode(201)
                .body("name", equalTo("privatelink.blob.core.windows.net"))
                .body("type", equalTo("Microsoft.Network/privateDnsZones"))
                .body("location", equalTo("global"))
                .body("tags.project", equalTo("floci"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.numberOfRecordSets", equalTo(1))
                .body("properties.numberOfVirtualNetworkLinks", equalTo(0))
                .body("properties.maxNumberOfVirtualNetworkLinks", equalTo(1000))
                .body("properties.nameServers", equalTo(null));

        // 2. Default SOA record is seeded (no NS root for private zones)
        given().when().get(BASE + "/privateDnsZones/privatelink.blob.core.windows.net/SOA/@" + API)
                .then().statusCode(200)
                .body("type", equalTo("Microsoft.Network/privateDnsZones/SOA"))
                .body("properties.SOARecord.host", equalTo("azureprivatedns.net."));

        given().when().get(BASE + "/privateDnsZones/privatelink.blob.core.windows.net/NS/@" + API)
                .then().statusCode(404);

        // 3. Create A record
        String etag = given().contentType("application/json")
                .body("""
                      { "properties": { "ttl": 10, "aRecords": [{"ipv4Address": "10.0.0.4"}] } }
                      """)
                .when().put(BASE + "/privateDnsZones/privatelink.blob.core.windows.net/A/myaccount" + API)
                .then().statusCode(201)
                .body("type", equalTo("Microsoft.Network/privateDnsZones/A"))
                .body("properties.fqdn", equalTo("myaccount.privatelink.blob.core.windows.net."))
                .body("properties.ARecords[0].ipv4Address", equalTo("10.0.0.4"))
                .extract().path("etag");

        given().when().get(BASE + "/privateDnsZones/privatelink.blob.core.windows.net" + API)
                .then().statusCode(200)
                .body("properties.numberOfRecordSets", equalTo(2));

        // 4. ETag concurrency
        given().contentType("application/json").body("{\"properties\":{\"ttl\":10}}")
                .header("If-Match", "\"wrong\"")
                .when().put(BASE + "/privateDnsZones/privatelink.blob.core.windows.net/A/myaccount" + API)
                .then().statusCode(412);

        given().contentType("application/json").body("{\"properties\":{\"ttl\":10}}")
                .header("If-None-Match", "*")
                .when().put(BASE + "/privateDnsZones/privatelink.blob.core.windows.net/A/myaccount" + API)
                .then().statusCode(412);

        // 5. PTR record (private-DNS specific type)
        given().contentType("application/json")
                .body("""
                      { "properties": { "ttl": 3600, "ptrRecords": [{"ptrdname": "host.contoso.com."}] } }
                      """)
                .when().put(BASE + "/privateDnsZones/privatelink.blob.core.windows.net/PTR/4" + API)
                .then().statusCode(201)
                .body("type", equalTo("Microsoft.Network/privateDnsZones/PTR"));

        // 6. NS is not a valid private-DNS record type -> not found via dispatch
        given().contentType("application/json").body("{\"properties\":{\"ttl\":3600}}")
                .when().put(BASE + "/privateDnsZones/privatelink.blob.core.windows.net/NS/sub" + API)
                .then().statusCode(404);

        // 7. List all record sets (SOA@, A/myaccount, PTR/4)
        given().when().get(BASE + "/privateDnsZones/privatelink.blob.core.windows.net/recordsets" + API)
                .then().statusCode(200)
                .body("value", hasSize(3));

        // 8. Root SOA cannot be deleted
        given().when().delete(BASE + "/privateDnsZones/privatelink.blob.core.windows.net/SOA/@" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadRequest"));

        // 9. Delete A record -> count back to 1
        given().when().delete(BASE + "/privateDnsZones/privatelink.blob.core.windows.net/A/myaccount" + API)
                .then().statusCode(200);

        given().when().get(BASE + "/privateDnsZones/privatelink.blob.core.windows.net" + API)
                .then().statusCode(200)
                .body("properties.numberOfRecordSets", equalTo(2)); // SOA@ + PTR/4

        // 10. Delete zone cascades records
        given().when().delete(BASE + "/privateDnsZones/privatelink.blob.core.windows.net" + API)
                .then().statusCode(200);

        given().when().get(BASE + "/privateDnsZones/privatelink.blob.core.windows.net/PTR/4" + API)
                .then().statusCode(404);
    }

    @Test
    void privateDnsZoneVirtualNetworkLinkLifecycle() {
        given().contentType("application/json").body("{\"location\":\"global\"}")
                .when().put(BASE + "/privateDnsZones/contoso.internal" + API)
                .then().statusCode(201);

        String linkBody = """
                {
                  "location": "global",
                  "properties": {
                    "registrationEnabled": false,
                    "virtualNetwork": {
                      "id": "/subscriptions/test-sub-net/resourceGroups/test-rg-net/providers/Microsoft.Network/virtualNetworks/vnet1"
                    }
                  }
                }
                """;

        given().contentType("application/json").body(linkBody)
                .when().put(BASE + "/privateDnsZones/contoso.internal/virtualNetworkLinks/link1" + API)
                .then().statusCode(201)
                .body("name", equalTo("link1"))
                .body("type", equalTo("Microsoft.Network/privateDnsZones/virtualNetworkLinks"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.virtualNetworkLinkState", equalTo("Completed"))
                .body("properties.registrationEnabled", equalTo(false));

        given().when().get(BASE + "/privateDnsZones/contoso.internal" + API)
                .then().statusCode(200)
                .body("properties.numberOfVirtualNetworkLinks", equalTo(1));

        given().when().get(BASE + "/privateDnsZones/contoso.internal/virtualNetworkLinks" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("link1"));

        given().when().delete(BASE + "/privateDnsZones/contoso.internal/virtualNetworkLinks/link1" + API)
                .then().statusCode(200);

        given().when().get(BASE + "/privateDnsZones/contoso.internal" + API)
                .then().statusCode(200)
                .body("properties.numberOfVirtualNetworkLinks", equalTo(0));
    }

    @Test
    void privateEndpointLifecycleAndNicSynthesis() {
        String peBody = """
                {
                  "location": "eastus",
                  "properties": {
                    "subnet": {
                      "id": "/subscriptions/test-sub-net/resourceGroups/test-rg-net/providers/Microsoft.Network/virtualNetworks/vnet1/subnets/pe-subnet"
                    },
                    "privateLinkServiceConnections": [
                      {
                        "name": "conn1",
                        "properties": {
                          "privateLinkServiceId": "/subscriptions/test-sub-net/resourceGroups/test-rg-net/providers/Microsoft.Storage/storageAccounts/sa1",
                          "groupIds": ["blob"]
                        }
                      }
                    ]
                  }
                }
                """;

        String nicId = given().contentType("application/json").body(peBody)
                .when().put(BASE + "/privateEndpoints/pe1" + API)
                .then().statusCode(201)
                .body("name", equalTo("pe1"))
                .body("type", equalTo("Microsoft.Network/privateEndpoints"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.privateLinkServiceConnections[0].id",
                        equalTo("/subscriptions/test-sub-net/resourceGroups/test-rg-net/providers/Microsoft.Network/privateEndpoints/pe1/privateLinkServiceConnections/conn1"))
                .body("properties.privateLinkServiceConnections[0].properties.privateLinkServiceConnectionState.status", equalTo("Approved"))
                .body("properties.privateLinkServiceConnections[0].properties.privateLinkServiceConnectionState.actionsRequired", equalTo("None"))
                .body("properties.networkInterfaces[0].id", org.hamcrest.Matchers.notNullValue())
                .extract().path("properties.networkInterfaces[0].id");

        // Synthesized NIC is retrievable and carries a private IP
        String nicTail = nicId.substring(nicId.indexOf("/providers/Microsoft.Network/") + "/providers/Microsoft.Network/".length());
        given().when().get(BASE + "/" + nicTail + API)
                .then().statusCode(200)
                .body("type", equalTo("Microsoft.Network/networkInterfaces"))
                .body("properties.ipConfigurations[0].properties.privateIPAddress", equalTo("10.0.0.4"));

        // Private DNS zone group
        given().contentType("application/json")
                .body("""
                      {
                        "properties": {
                          "privateDnsZoneConfigs": [
                            {
                              "name": "config1",
                              "properties": {
                                "privateDnsZoneId": "/subscriptions/test-sub-net/resourceGroups/test-rg-net/providers/Microsoft.Network/privateDnsZones/privatelink.blob.core.windows.net"
                              }
                            }
                          ]
                        }
                      }
                      """)
                .when().put(BASE + "/privateEndpoints/pe1/privateDnsZoneGroups/default" + API)
                .then().statusCode(201)
                .body("type", equalTo("Microsoft.Network/privateEndpoints/privateDnsZoneGroups"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.privateDnsZoneConfigs[0].properties.privateDnsZoneId", org.hamcrest.Matchers.notNullValue());

        given().when().get(BASE + "/privateEndpoints/pe1/privateDnsZoneGroups" + API)
                .then().statusCode(200)
                .body("value", hasSize(1));

        // Delete endpoint cascades NIC + zone group
        given().when().delete(BASE + "/privateEndpoints/pe1" + API)
                .then().statusCode(200);

        given().when().get(BASE + "/" + nicTail + API)
                .then().statusCode(404);

        given().when().get(BASE + "/privateEndpoints/pe1/privateDnsZoneGroups/default" + API)
                .then().statusCode(404);
    }

    @Test
    void privateLinkServiceSynthesizesAlias() {
        String plsBody = """
                {
                  "location": "eastus",
                  "properties": {
                    "loadBalancerFrontendIpConfigurations": [
                      {"id": "/subscriptions/test-sub-net/resourceGroups/test-rg-net/providers/Microsoft.Network/loadBalancers/lb/frontendIPConfigurations/fe"}
                    ]
                  }
                }
                """;

        given().contentType("application/json").body(plsBody)
                .when().put(BASE + "/privateLinkServices/pls1" + API)
                .then().statusCode(200)
                .body("name", equalTo("pls1"))
                .body("type", equalTo("Microsoft.Network/privateLinkServices"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.alias", org.hamcrest.Matchers.notNullValue());

        given().when().get(BASE + "/privateLinkServices" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("pls1"));
    }

    @Test
    void privateEndpointUpdateReusesNicAndDoesNotLeak() {
        String peBody = """
                {
                  "location": "eastus",
                  "properties": {
                    "privateLinkServiceConnections": [
                      {"name": "conn1", "properties": {
                        "privateLinkServiceId": "/subscriptions/test-sub-net/resourceGroups/test-rg-net/providers/Microsoft.Storage/storageAccounts/sa1",
                        "groupIds": ["blob"]}}
                    ]
                  }
                }
                """;

        // Create (201), then update twice (200) — Azure keeps a single stable NIC.
        String firstNic = given().contentType("application/json").body(peBody)
                .when().put(BASE + "/privateEndpoints/pe1" + API)
                .then().statusCode(201)
                .extract().path("properties.networkInterfaces[0].id");

        for (int i = 0; i < 2; i++) {
            given().contentType("application/json").body(peBody)
                    .when().put(BASE + "/privateEndpoints/pe1" + API)
                    .then().statusCode(200)
                    .body("properties.networkInterfaces[0].id", equalTo(firstNic));
        }

        // Exactly one synthesized NIC exists — no per-update leak.
        given().when().get(BASE + "/networkInterfaces" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].id", equalTo(firstNic));

        // Cascade delete removes it — nothing orphaned.
        given().when().delete(BASE + "/privateEndpoints/pe1" + API)
                .then().statusCode(200);

        given().when().get(BASE + "/networkInterfaces" + API)
                .then().statusCode(200)
                .body("value", hasSize(0));

        given().when().get(BASE + "/privateEndpoints" + API)
                .then().statusCode(200)
                .body("value", hasSize(0));
    }
}
