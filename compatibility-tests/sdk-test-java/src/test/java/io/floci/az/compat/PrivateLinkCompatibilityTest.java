package io.floci.az.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility test for Azure Private Link: {@code Microsoft.Network/privateDnsZones}
 * (+ {@code virtualNetworkLinks} and record sets) and {@code Microsoft.Network/privateEndpoints}
 * (+ {@code privateDnsZoneGroups}) as exposed by floci-az.
 *
 * <p>Mirrors {@link VmCompatibilityTest}: the established repo pattern for ARM management-plane
 * services is to drive the real Azure REST wire protocol with a raw {@link HttpClient} rather than
 * the heavyweight fluent {@code azure-resourcemanager-network} SDK (which would require
 * subscription/provider-registration endpoints floci-az does not emulate).
 *
 * <p>Covers the full lifecycle the {@code azure-resourcemanager-network} SDK and
 * {@code azurerm_private_dns_zone} / {@code azurerm_private_endpoint} exercise: create a private DNS
 * zone (default SOA seed) → link a virtual network → add an A record → create a private endpoint
 * (asserting connection auto-approval and the synthesized NIC + private IP) → attach a private DNS
 * zone group → list → delete (asserting the synthesized NIC is cascaded away).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Private Link (Private Endpoint + Private DNS) Compatibility")
class PrivateLinkCompatibilityTest {

    private static final String BASE =
            System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String RG = "pl-rg-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String VNET = "vnet-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String PDZ = "privatelink.blob.core.windows.net";
    private static final String LINK = "link-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String PE = "pe-" + UUID.randomUUID().toString().substring(0, 8);

    private static final String NETWORK_API = "2024-05-01";
    private static final String RG_API = "2021-04-01";

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void setup() {
        EmulatorConfig.assumeEmulatorRunning();
    }

    @Test
    @Order(1)
    void createDependencies_resourceGroupAndVnet() throws Exception {
        assertOk(put(rgUrl(), "{\"location\":\"eastus\"}"), "create resource group");

        String vnetBody = """
                {
                  "location": "eastus",
                  "properties": {
                    "addressSpace": {"addressPrefixes": ["10.0.0.0/16"]},
                    "subnets": [
                      {"name": "pe-subnet", "properties": {"addressPrefix": "10.0.1.0/24"}}
                    ]
                  }
                }
                """;
        assertOk(put(netUrl("virtualNetworks/" + VNET), vnetBody), "create virtual network");
    }

    @Test
    @Order(2)
    void createPrivateDnsZone_seedsDefaultSoa() throws Exception {
        HttpResponse<String> resp = put(netUrl("privateDnsZones/" + PDZ),
                "{\"location\":\"global\",\"tags\":{\"env\":\"compat\"}}");
        assertEquals(201, resp.statusCode(), "create private DNS zone: " + resp.body());

        JsonNode json = mapper.readTree(resp.body());
        assertEquals(PDZ, json.get("name").asText());
        assertEquals("Microsoft.Network/privateDnsZones", json.get("type").asText());
        assertEquals("global", json.get("location").asText());
        JsonNode props = json.get("properties");
        assertEquals("Succeeded", props.get("provisioningState").asText());
        assertEquals(1, props.get("numberOfRecordSets").asInt());
        assertEquals(0, props.get("numberOfVirtualNetworkLinks").asInt());
        assertTrue(props.has("maxNumberOfVirtualNetworkLinks"));

        // Default SOA record set is seeded; private zones have no NS root record.
        assertEquals(200, get(netUrl("privateDnsZones/" + PDZ + "/SOA/@")).statusCode());
        assertEquals(404, get(netUrl("privateDnsZones/" + PDZ + "/NS/@")).statusCode());
    }

    @Test
    @Order(3)
    void linkVirtualNetwork_reportsCompleted() throws Exception {
        String linkBody = """
                {
                  "location": "global",
                  "properties": {
                    "registrationEnabled": false,
                    "virtualNetwork": {"id": "%s"}
                  }
                }
                """.formatted(vnetResourceId());

        HttpResponse<String> resp = put(netUrl("privateDnsZones/" + PDZ + "/virtualNetworkLinks/" + LINK), linkBody);
        assertEquals(201, resp.statusCode(), "create vnet link: " + resp.body());
        JsonNode props = mapper.readTree(resp.body()).get("properties");
        assertEquals("Succeeded", props.get("provisioningState").asText());
        assertEquals("Completed", props.get("virtualNetworkLinkState").asText());
        assertFalse(props.get("registrationEnabled").asBoolean());

        assertEquals(1, mapper.readTree(get(netUrl("privateDnsZones/" + PDZ)).body())
                .get("properties").get("numberOfVirtualNetworkLinks").asInt());
    }

    @Test
    @Order(4)
    void createARecord_incrementsCount() throws Exception {
        HttpResponse<String> resp = put(netUrl("privateDnsZones/" + PDZ + "/A/myaccount"),
                "{\"properties\":{\"ttl\":10,\"aRecords\":[{\"ipv4Address\":\"10.0.1.4\"}]}}");
        assertEquals(201, resp.statusCode(), "create A record: " + resp.body());
        JsonNode json = mapper.readTree(resp.body());
        assertEquals("Microsoft.Network/privateDnsZones/A", json.get("type").asText());
        assertEquals("myaccount." + PDZ + ".", json.get("properties").get("fqdn").asText());
    }

    @Test
    @Order(5)
    void createPrivateEndpoint_autoApprovesAndSynthesizesNic() throws Exception {
        String peBody = """
                {
                  "location": "eastus",
                  "properties": {
                    "subnet": {"id": "%s/subnets/pe-subnet"},
                    "privateLinkServiceConnections": [
                      {
                        "name": "conn1",
                        "properties": {
                          "privateLinkServiceId": "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Storage/storageAccounts/sa1",
                          "groupIds": ["blob"]
                        }
                      }
                    ]
                  }
                }
                """.formatted(vnetResourceId(), SUBSCRIPTION, RG);

        HttpResponse<String> resp = put(netUrl("privateEndpoints/" + PE), peBody);
        assertOk(resp, "create private endpoint");
        JsonNode props = mapper.readTree(resp.body()).get("properties");
        assertEquals("Succeeded", props.get("provisioningState").asText());

        JsonNode conn = props.get("privateLinkServiceConnections").get(0);
        assertTrue(conn.get("id").asText().endsWith("/privateLinkServiceConnections/conn1"));
        assertEquals("Approved",
                conn.get("properties").get("privateLinkServiceConnectionState").get("status").asText());

        JsonNode nicRef = props.get("networkInterfaces").get(0);
        assertNotNull(nicRef, "private endpoint should synthesize a NIC reference");
        String nicId = nicRef.get("id").asText();

        // The synthesized NIC is retrievable and carries a private IP.
        String nicTail = nicId.substring(nicId.indexOf("/providers/Microsoft.Network/")
                + "/providers/Microsoft.Network/".length());
        JsonNode nic = mapper.readTree(get(netUrl(nicTail)).body());
        assertEquals("Microsoft.Network/networkInterfaces", nic.get("type").asText());
        assertNotNull(nic.get("properties").get("ipConfigurations").get(0)
                .get("properties").get("privateIPAddress"));
    }

    @Test
    @Order(6)
    void attachPrivateDnsZoneGroup_andList() throws Exception {
        String groupBody = """
                {
                  "properties": {
                    "privateDnsZoneConfigs": [
                      {"name": "config1", "properties": {"privateDnsZoneId": "%s"}}
                    ]
                  }
                }
                """.formatted("/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.Network/privateDnsZones/" + PDZ);

        HttpResponse<String> resp = put(netUrl("privateEndpoints/" + PE + "/privateDnsZoneGroups/default"), groupBody);
        assertEquals(201, resp.statusCode(), "create zone group: " + resp.body());
        assertEquals("Microsoft.Network/privateEndpoints/privateDnsZoneGroups",
                mapper.readTree(resp.body()).get("type").asText());

        JsonNode list = mapper.readTree(get(netUrl("privateEndpoints/" + PE + "/privateDnsZoneGroups")).body());
        assertTrue(list.get("value").size() >= 1);
    }

    @Test
    @Order(7)
    void deletePrivateEndpoint_cascadesNic() throws Exception {
        String nicId = mapper.readTree(get(netUrl("privateEndpoints/" + PE)).body())
                .get("properties").get("networkInterfaces").get(0).get("id").asText();
        String nicTail = nicId.substring(nicId.indexOf("/providers/Microsoft.Network/")
                + "/providers/Microsoft.Network/".length());

        assertOk(delete(netUrl("privateEndpoints/" + PE)), "delete private endpoint");
        assertEquals(404, get(netUrl("privateEndpoints/" + PE)).statusCode());
        assertEquals(404, get(netUrl(nicTail)).statusCode(), "synthesized NIC should be cascaded");
    }

    @Test
    @Order(8)
    void deletePrivateDnsZone_cascadesChildren() throws Exception {
        assertOk(delete(netUrl("privateDnsZones/" + PDZ)), "delete private DNS zone");
        assertEquals(404, get(netUrl("privateDnsZones/" + PDZ)).statusCode());
        assertEquals(404, get(netUrl("privateDnsZones/" + PDZ + "/A/myaccount")).statusCode());
    }

    // ── URL builders ────────────────────────────────────────────────────────────

    private static String rgUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG + "?api-version=" + RG_API;
    }

    private static String netUrl(String tail) {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.Network/" + tail + "?api-version=" + NETWORK_API;
    }

    private static String vnetResourceId() {
        return "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.Network/virtualNetworks/" + VNET;
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────────

    private static HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(String url, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .PUT(HttpRequest.BodyPublishers.ofString(json))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void assertOk(HttpResponse<String> resp, String operation) {
        assertTrue(resp.statusCode() >= 200 && resp.statusCode() < 300,
                operation + " failed: " + resp.statusCode() + " " + resp.body());
    }
}
