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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility test for Azure Virtual Machines (Microsoft.Compute/virtualMachines) and the
 * Microsoft.Network dependency stubs (virtualNetworks / networkInterfaces) exposed by floci-az.
 *
 * <p>Mirrors {@link ApiManagementCompatibilityTest}: the established repo pattern for ARM
 * management-plane services is to drive the real Azure REST wire protocol with a raw
 * {@link HttpClient} rather than the heavyweight fluent {@code azure-resourcemanager-*} SDK
 * (which would require subscription/provider-registration endpoints floci-az does not emulate).
 *
 * <p>Covers the full lifecycle the {@code azure-mgmt-compute} / {@code azure-resourcemanager-compute}
 * SDKs and {@code azurerm_linux_virtual_machine} exercise: create (with vnet/subnet/nic
 * dependencies) → get → {@code $expand=instanceView} → instanceView → power actions
 * (powerOff / start / deallocate, asserting the {@code Azure-AsyncOperation} LRO header and the
 * resulting PowerState) → list by resource group and by subscription → delete.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Virtual Machines Compatibility")
class VmCompatibilityTest {

    private static final String BASE =
            System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String RG = "vm-rg-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String VM = "vm-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String VNET = "vnet-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String NIC = VM + "-nic";

    private static final String COMPUTE_API = "2024-11-01";
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
    void createNetworkDependencies_returnSucceeded() throws Exception {
        assertOk(put(resourceGroupUrl(), "{\"location\":\"eastus\"}"), "create resource group");

        String vnetBody = """
                {
                  "location": "eastus",
                  "properties": {
                    "addressSpace": {"addressPrefixes": ["10.0.0.0/16"]},
                    "subnets": [
                      {"name": "default", "properties": {"addressPrefix": "10.0.0.0/24"}}
                    ]
                  }
                }
                """;
        HttpResponse<String> vnetResp = put(vnetUrl(), vnetBody);
        assertOk(vnetResp, "create virtual network");
        assertEquals("Succeeded",
                mapper.readTree(vnetResp.body()).get("properties").get("provisioningState").asText());

        String nicBody = """
                {
                  "location": "eastus",
                  "properties": {
                    "ipConfigurations": [
                      {"name": "ipconfig1", "properties": {"subnet": {"id": "%s/subnets/default"}}}
                    ]
                  }
                }
                """.formatted(vnetResourceId());
        HttpResponse<String> nicResp = put(nicUrl(), nicBody);
        assertOk(nicResp, "create network interface");
        JsonNode nic = mapper.readTree(nicResp.body());
        assertEquals("Succeeded", nic.get("properties").get("provisioningState").asText());
        assertNotNull(nic.get("properties").get("ipConfigurations").get(0)
                        .get("properties").get("privateIPAddress"),
                "NIC stub should synthesize a privateIPAddress");
    }

    @Test
    @Order(2)
    void createVm_returnsSucceededWithEchoedProperties() throws Exception {
        String body = """
                {
                  "location": "eastus",
                  "tags": {"env": "compat"},
                  "properties": {
                    "hardwareProfile": {"vmSize": "Standard_D2s_v3"},
                    "storageProfile": {
                      "imageReference": {
                        "publisher": "Canonical",
                        "offer": "0001-com-ubuntu-server-jammy",
                        "sku": "22_04-lts",
                        "version": "latest"
                      },
                      "osDisk": {"createOption": "FromImage", "name": "%s-osdisk"}
                    },
                    "osProfile": {"adminUsername": "azureuser", "computerName": "%s"},
                    "networkProfile": {
                      "networkInterfaces": [{"id": "%s"}]
                    }
                  }
                }
                """.formatted(VM, VM, nicResourceId());

        HttpResponse<String> resp = put(vmUrl(), body);
        assertEquals(201, resp.statusCode(), "create VM: " + resp.body());

        JsonNode json = mapper.readTree(resp.body());
        assertEquals(VM, json.get("name").asText());
        assertEquals("Microsoft.Compute/virtualMachines", json.get("type").asText());
        assertEquals("eastus", json.get("location").asText());
        assertEquals("compat", json.get("tags").get("env").asText());
        JsonNode props = json.get("properties");
        assertEquals("Succeeded", props.get("provisioningState").asText());
        assertTrue(props.get("vmId").asText().length() > 0, "vmId should be synthesized");
        assertEquals("Standard_D2s_v3", props.get("hardwareProfile").get("vmSize").asText());
        assertEquals("azureuser", props.get("osProfile").get("adminUsername").asText());
        assertEquals(nicResourceId(),
                props.get("networkProfile").get("networkInterfaces").get(0).get("id").asText());
    }

    @Test
    @Order(3)
    void getVm_andExpandInstanceView_reportRunning() throws Exception {
        HttpResponse<String> getResp = get(vmUrl());
        assertEquals(200, getResp.statusCode(), getResp.body());
        assertEquals(VM, mapper.readTree(getResp.body()).get("name").asText());

        HttpResponse<String> expandResp = get(vmUrl() + "&$expand=instanceView");
        assertEquals(200, expandResp.statusCode(), expandResp.body());
        JsonNode statuses = mapper.readTree(expandResp.body())
                .get("properties").get("instanceView").get("statuses");
        assertHasStatusCode(statuses, "PowerState/running");
    }

    @Test
    @Order(4)
    void instanceView_reportsProvisioningAndPowerState() throws Exception {
        HttpResponse<String> resp = get(vmUrl("/instanceView"));
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode statuses = mapper.readTree(resp.body()).get("statuses");
        assertHasStatusCode(statuses, "ProvisioningState/succeeded");
        assertHasStatusCode(statuses, "PowerState/running");
    }

    @Test
    @Order(5)
    void powerActions_emitAsyncHeaderAndChangePowerState() throws Exception {
        HttpResponse<String> off = post(vmUrl("/powerOff"));
        assertEquals(202, off.statusCode(), off.body());
        assertTrue(off.headers().firstValue("Azure-AsyncOperation").orElse("").contains("/operations/"),
                "powerOff must return an Azure-AsyncOperation header for SDK LRO polling");
        assertTrue(off.headers().firstValue("Location").isPresent(),
                "powerOff must return a Location header (final-state-via: location)");
        assertPowerState("PowerState/stopped");

        assertEquals(202, post(vmUrl("/start")).statusCode());
        assertPowerState("PowerState/running");

        assertEquals(202, post(vmUrl("/deallocate")).statusCode());
        assertPowerState("PowerState/deallocated");

        assertEquals(202, post(vmUrl("/restart")).statusCode());
        assertPowerState("PowerState/running");
    }

    @Test
    @Order(6)
    void asyncOperationStatus_returnsSucceeded() throws Exception {
        HttpResponse<String> action = post(vmUrl("/start"));
        String asyncUrl = action.headers().firstValue("Azure-AsyncOperation").orElseThrow();
        HttpResponse<String> statusResp = get(asyncUrl);
        assertEquals(200, statusResp.statusCode(), statusResp.body());
        assertEquals("Succeeded", mapper.readTree(statusResp.body()).get("status").asText());
    }

    @Test
    @Order(7)
    void listVms_bySubscriptionAndResourceGroup() throws Exception {
        HttpResponse<String> rgList = get(vmCollectionInRgUrl());
        assertEquals(200, rgList.statusCode(), rgList.body());
        assertContainsName(mapper.readTree(rgList.body()).get("value"), VM);

        HttpResponse<String> subList = get(vmCollectionInSubUrl());
        assertEquals(200, subList.statusCode(), subList.body());
        assertContainsName(mapper.readTree(subList.body()).get("value"), VM);
    }

    @Test
    @Order(8)
    void deleteVm_thenGetReturns404() throws Exception {
        assertOk(delete(vmUrl()), "delete VM");
        assertEquals(404, get(vmUrl()).statusCode());
        // Delete is idempotent.
        assertOk(delete(vmUrl()), "delete VM (idempotent)");
    }

    // ── URL builders ────────────────────────────────────────────────────────────

    private static String resourceGroupUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "?api-version=" + RG_API;
    }

    private static String computeProviderUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.Compute";
    }

    private static String vmUrl() {
        return computeProviderUrl() + "/virtualMachines/" + VM + "?api-version=" + COMPUTE_API;
    }

    private static String vmUrl(String childPath) {
        return computeProviderUrl() + "/virtualMachines/" + VM + childPath
                + "?api-version=" + COMPUTE_API;
    }

    private static String vmCollectionInRgUrl() {
        return computeProviderUrl() + "/virtualMachines?api-version=" + COMPUTE_API;
    }

    private static String vmCollectionInSubUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION
                + "/providers/Microsoft.Compute/virtualMachines?api-version=" + COMPUTE_API;
    }

    private static String vnetResourceId() {
        return "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.Network/virtualNetworks/" + VNET;
    }

    private static String vnetUrl() {
        return BASE + vnetResourceId() + "?api-version=" + NETWORK_API;
    }

    private static String nicResourceId() {
        return "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.Network/networkInterfaces/" + NIC;
    }

    private static String nicUrl() {
        return BASE + nicResourceId() + "?api-version=" + NETWORK_API;
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

    private static HttpResponse<String> post(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── Assertions ──────────────────────────────────────────────────────────────

    private static void assertPowerState(String code) throws Exception {
        HttpResponse<String> resp = get(vmUrl("/instanceView"));
        assertEquals(200, resp.statusCode(), resp.body());
        assertHasStatusCode(mapper.readTree(resp.body()).get("statuses"), code);
    }

    private static void assertHasStatusCode(JsonNode statuses, String code) {
        assertNotNull(statuses, "statuses array missing");
        assertTrue(statuses.isArray(), "Expected statuses array but got " + statuses);
        for (JsonNode s : statuses) {
            if (code.equals(s.get("code").asText())) {
                return;
            }
        }
        throw new AssertionError("Expected statuses to contain code " + code + ": " + statuses);
    }

    private static void assertContainsName(JsonNode array, String name) {
        assertNotNull(array);
        assertTrue(array.isArray(), "Expected array but got " + array);
        for (JsonNode item : array) {
            if (name.equals(item.get("name").asText())) {
                return;
            }
        }
        throw new AssertionError("Expected list to contain name " + name + ": " + array);
    }

    private static void assertOk(HttpResponse<String> resp, String operation) {
        assertTrue(resp.statusCode() >= 200 && resp.statusCode() < 300,
                operation + " failed: " + resp.statusCode() + " " + resp.body());
    }
}
