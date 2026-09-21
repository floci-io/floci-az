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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility test for Azure Container Instances
 * (Microsoft.ContainerInstance/containerGroups) exposed by floci-az.
 *
 * <p>Mirrors {@link VmCompatibilityTest}: ARM management-plane services are driven with a raw
 * {@link HttpClient} on the real Azure REST wire protocol rather than the fluent
 * {@code azure-resourcemanager-*} SDK.
 *
 * <p>Beyond the lifecycle, this pins the read-back contract the {@code azurerm_container_group}
 * Terraform resource depends on: container {@code ports} and {@code resources.requests} always
 * present, canonical enum casing, secure environment values and registry passwords never echoed,
 * and the spec's exact LRO shapes (start 202 + Location, restart 204 + Location, stop bare 204,
 * delete synchronous 200, 204 once absent).</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Container Instances Compatibility")
class ContainerInstanceCompatibilityTest {

    private static final String BASE =
            System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String RG = "aci-rg-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String GROUP = "cg-" + UUID.randomUUID().toString().substring(0, 8);

    private static final String ACI_API = "2023-05-01";
    private static final String RG_API = "2021-04-01";

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void setup() {
        EmulatorConfig.assumeEmulatorRunning();
    }

    @Test
    @Order(1)
    void createContainerGroup_returnsSucceededWithNormalizedReadback() throws Exception {
        assertOk(put(resourceGroupUrl(), "{\"location\":\"eastus\"}"), "create resource group");

        String body = """
                {
                  "location": "eastus",
                  "tags": {"env": "compat"},
                  "properties": {
                    "containers": [
                      {
                        "name": "web",
                        "properties": {
                          "image": "hashicorp/http-echo:latest",
                          "command": ["/http-echo", "-text=hi"],
                          "ports": [{"port": 5678, "protocol": "tcp"}],
                          "environmentVariables": [
                            {"name": "PLAIN", "value": "visible"},
                            {"name": "SECRET", "secureValue": "hunter2"}
                          ]
                        }
                      }
                    ],
                    "osType": "linux",
                    "imageRegistryCredentials": [
                      {"server": "example.azurecr.io", "username": "admin", "password": "p"}
                    ],
                    "ipAddress": {"type": "Public", "ports": [{"port": 5678}], "dnsNameLabel": "compat"}
                  }
                }
                """;

        HttpResponse<String> resp = put(groupUrl(), body);
        assertEquals(201, resp.statusCode(), "create container group: " + resp.body());

        JsonNode json = mapper.readTree(resp.body());
        assertEquals(GROUP, json.get("name").asText());
        assertEquals("Microsoft.ContainerInstance/containerGroups", json.get("type").asText());
        JsonNode props = json.get("properties");
        assertEquals("Succeeded", props.get("provisioningState").asText());

        // azurerm read-back contract: canonical casing + defaulted resources.
        assertEquals("Linux", props.get("osType").asText());
        assertEquals("Always", props.get("restartPolicy").asText());
        JsonNode container = props.get("containers").get(0).get("properties");
        assertEquals("TCP", container.get("ports").get(0).get("protocol").asText());
        assertNotNull(container.get("resources").get("requests").get("cpu"));
        assertNotNull(container.get("resources").get("requests").get("memoryInGB"));

        // Secrets are write-only.
        JsonNode secretVar = container.get("environmentVariables").get(1);
        assertEquals("SECRET", secretVar.get("name").asText());
        assertNull(secretVar.get("secureValue"), "secureValue must not be echoed");
        assertNull(props.get("imageRegistryCredentials").get(0).get("password"),
                "registry password must not be echoed");

        assertEquals("compat.eastus.azurecontainer.io",
                props.get("ipAddress").get("fqdn").asText());
    }

    @Test
    @Order(2)
    void getContainerGroup_includesInstanceView() throws Exception {
        HttpResponse<String> resp = get(groupUrl());
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode props = mapper.readTree(resp.body()).get("properties");
        assertNotNull(props.get("instanceView"), "single GET must include instanceView");
        assertNotNull(props.get("containers").get(0).get("properties").get("instanceView"),
                "containers must carry instanceView on single GET");
    }

    @Test
    @Order(3)
    void listContainerGroups_omitInstanceView() throws Exception {
        HttpResponse<String> rgList = get(groupCollectionInRgUrl());
        assertEquals(200, rgList.statusCode(), rgList.body());
        JsonNode entry = findByName(mapper.readTree(rgList.body()).get("value"), GROUP);
        assertNull(entry.get("properties").get("instanceView"),
                "list responses must omit instanceView (ListResultContainerGroup shape)");

        HttpResponse<String> subList = get(groupCollectionInSubUrl());
        assertEquals(200, subList.statusCode(), subList.body());
        findByName(mapper.readTree(subList.body()).get("value"), GROUP);
    }

    @Test
    @Order(4)
    void actions_matchSpecLroShapes() throws Exception {
        HttpResponse<String> start = post(groupUrl("/start"));
        assertEquals(202, start.statusCode(), start.body());
        String location = start.headers().firstValue("Location").orElse("");
        assertTrue(location.contains("/providers/Microsoft.ContainerInstance/locations/"),
                "start must return a Location operation URL, got: " + location);

        HttpResponse<String> opStatus = get(location);
        assertEquals(200, opStatus.statusCode(), opStatus.body());
        assertEquals("Succeeded", mapper.readTree(opStatus.body()).get("status").asText());

        HttpResponse<String> restart = post(groupUrl("/restart"));
        assertEquals(204, restart.statusCode(), "restart signals its LRO on a 204 per spec");
        assertTrue(restart.headers().firstValue("Location").isPresent(),
                "restart must carry a Location header despite the 204");

        HttpResponse<String> stop = post(groupUrl("/stop"));
        assertEquals(204, stop.statusCode(), "stop is the spec's only synchronous action");
        assertFalse(stop.headers().firstValue("Location").isPresent(),
                "stop must not carry LRO headers");
    }

    @Test
    @Order(5)
    void containerLogs_returnContentEnvelope() throws Exception {
        HttpResponse<String> logs = get(groupUrl("/containers/web/logs"));
        assertEquals(200, logs.statusCode(), logs.body());
        assertNotNull(mapper.readTree(logs.body()).get("content"), "Logs model is {content}");
    }

    @Test
    @Order(6)
    void exec_returnsHonest501() throws Exception {
        HttpResponse<String> exec = post(groupUrl("/containers/web/exec"),
                "{\"command\": \"/bin/sh\", \"terminalSize\": {\"rows\": 24, \"cols\": 80}}");
        assertEquals(501, exec.statusCode(), exec.body());
        assertEquals("NotImplemented",
                mapper.readTree(exec.body()).get("error").get("code").asText());
    }

    @Test
    @Order(7)
    void groupAppearsInResourceGroupIndex() throws Exception {
        HttpResponse<String> resp = get(BASE + "/subscriptions/" + SUBSCRIPTION
                + "/resourceGroups/" + RG + "/resources?api-version=" + RG_API);
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode entry = findByName(mapper.readTree(resp.body()).get("value"), GROUP);
        assertEquals("Microsoft.ContainerInstance/containerGroups", entry.get("type").asText());
    }

    @Test
    @Order(8)
    void deleteContainerGroup_synchronous200ThenGet404() throws Exception {
        HttpResponse<String> del = delete(groupUrl());
        assertEquals(200, del.statusCode(), "delete returns the deleted group's body: " + del.body());
        assertEquals(404, get(groupUrl()).statusCode());
        assertEquals(204, delete(groupUrl()).statusCode(), "delete is idempotent; 204 once absent");
    }

    // ── URL builders ────────────────────────────────────────────────────────────

    private static String resourceGroupUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "?api-version=" + RG_API;
    }

    private static String providerUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ContainerInstance";
    }

    private static String groupUrl() {
        return providerUrl() + "/containerGroups/" + GROUP + "?api-version=" + ACI_API;
    }

    private static String groupUrl(String childPath) {
        return providerUrl() + "/containerGroups/" + GROUP + childPath + "?api-version=" + ACI_API;
    }

    private static String groupCollectionInRgUrl() {
        return providerUrl() + "/containerGroups?api-version=" + ACI_API;
    }

    private static String groupCollectionInSubUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION
                + "/providers/Microsoft.ContainerInstance/containerGroups?api-version=" + ACI_API;
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

    private static HttpResponse<String> post(String url, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── Assertions ──────────────────────────────────────────────────────────────

    private static JsonNode findByName(JsonNode array, String name) {
        assertNotNull(array);
        assertTrue(array.isArray(), "Expected array but got " + array);
        for (JsonNode item : array) {
            if (name.equals(item.get("name").asText())) {
                return item;
            }
        }
        throw new AssertionError("Expected list to contain name " + name + ": " + array);
    }

    private static void assertOk(HttpResponse<String> resp, String operation) {
        assertTrue(resp.statusCode() >= 200 && resp.statusCode() < 300,
                operation + " failed: " + resp.statusCode() + " " + resp.body());
    }
}
