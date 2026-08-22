package io.floci.az.services.aci;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Quarkus-level tests for {@link AciHandler}, exercising the
 * Microsoft.ContainerInstance/containerGroups ARM surface in mocked mode (no Docker).
 *
 * <p>Beyond the CRUD lifecycle, this pins the azurerm-provider fidelity rules: the provider's
 * flatten code dereferences {@code containers[*].properties.ports} and
 * {@code resources.requests} without nil checks (a missing field panics terraform), and echoes
 * enum casing verbatim into state (non-canonical casing means a perpetual diff).</p>
 */
@QuarkusTest
@TestProfile(AciHandlerTest.MockedProfile.class)
@DisplayName("AciHandler — container group lifecycle and azurerm fidelity (mocked mode)")
@SuppressWarnings("unused")
class AciHandlerTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.aci.mocked", "true");
        }
    }

    private static final String SUB  = "test-sub-aci";
    private static final String RG   = "test-rg-aci";
    private static final String API  = "?api-version=2023-05-01";
    private static final String BASE =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.ContainerInstance";
    private static final String SUB_BASE =
            "/subscriptions/" + SUB + "/providers/Microsoft.ContainerInstance";

    private static final String CREATE_BODY = """
            {
              "location": "eastus",
              "tags": {"env": "test"},
              "properties": {
                "containers": [
                  {
                    "name": "web",
                    "properties": {
                      "image": "hashicorp/http-echo:latest",
                      "command": ["/http-echo", "-text=hello"],
                      "ports": [{"port": 5678, "protocol": "tcp"}],
                      "environmentVariables": [
                        {"name": "PLAIN", "value": "visible"},
                        {"name": "SECRET", "secureValue": "hunter2"}
                      ],
                      "resources": {"requests": {"cpu": 0.5, "memoryInGB": 0.5}}
                    }
                  }
                ],
                "osType": "linux",
                "restartPolicy": "onfailure",
                "imageRegistryCredentials": [
                  {"server": "myregistry.azurecr.io", "username": "admin", "password": "s3cret"}
                ],
                "ipAddress": {
                  "type": "public",
                  "ports": [{"port": 5678, "protocol": "tcp"}],
                  "dnsNameLabel": "myapp"
                }
              }
            }
            """;

    /** The az CLI omits resources entirely and relies on server-side defaults. */
    private static final String MINIMAL_BODY = """
            {
              "location": "westus",
              "properties": {
                "containers": [
                  {"name": "app", "properties": {"image": "busybox:latest"}}
                ]
              }
            }
            """;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private void createGroup(String name) {
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(BASE + "/containerGroups/" + name + API)
                .then().statusCode(201);
    }

    // ── CRUD lifecycle ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET unknown group returns 404 ResourceNotFound")
    void getUnknownGroupReturns404() {
        given().when().get(BASE + "/containerGroups/no-such-group" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    @DisplayName("PUT creates group (201) with Succeeded state and echoed properties")
    void createGroupReturns201() {
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(BASE + "/containerGroups/cg1" + API)
                .then().statusCode(201)
                .body("name", equalTo("cg1"))
                .body("type", equalTo("Microsoft.ContainerInstance/containerGroups"))
                .body("location", equalTo("eastus"))
                .body("tags.env", equalTo("test"))
                .body("id", equalTo("/subscriptions/" + SUB + "/resourceGroups/" + RG
                        + "/providers/Microsoft.ContainerInstance/containerGroups/cg1"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.containers[0].name", equalTo("web"))
                .body("properties.containers[0].properties.image", equalTo("hashicorp/http-echo:latest"))
                .body("properties.containers[0].properties.command", contains("/http-echo", "-text=hello"));
    }

    @Test
    @DisplayName("PUT existing group returns 200 (update)")
    void updateGroupReturns200() {
        createGroup("cg-upd");
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(BASE + "/containerGroups/cg-upd" + API)
                .then().statusCode(200)
                .body("properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    @DisplayName("PATCH updates tags and returns 200 with the full resource")
    void patchUpdatesTags() {
        createGroup("cg-patch");
        given().contentType("application/json").body("{\"tags\": {\"env\": \"patched\"}}")
                .when().patch(BASE + "/containerGroups/cg-patch" + API)
                .then().statusCode(200)
                .body("tags.env", equalTo("patched"))
                .body("properties.containers[0].name", equalTo("web"));
    }

    @Test
    @DisplayName("DELETE is a synchronous 204 and a subsequent GET is 404")
    void deleteThenGet404() {
        createGroup("cg-del");
        given().when().delete(BASE + "/containerGroups/cg-del" + API)
                .then().statusCode(204);
        given().when().get(BASE + "/containerGroups/cg-del" + API)
                .then().statusCode(404);
        // Idempotent for an already-absent group.
        given().when().delete(BASE + "/containerGroups/cg-del" + API)
                .then().statusCode(204);
    }

    @Test
    @DisplayName("List by resource group and by subscription both find the group")
    void listBothScopes() {
        createGroup("cg-list");
        given().when().get(BASE + "/containerGroups" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("cg-list"));
        given().when().get(SUB_BASE + "/containerGroups" + API)
                .then().statusCode(200)
                .body("value", hasSize(1));
    }

    // ── azurerm fidelity rules ─────────────────────────────────────────────────

    @Test
    @DisplayName("Enum casing is normalized to canonical Azure values")
    void enumCasingIsCanonical() {
        createGroup("cg-enum");
        given().when().get(BASE + "/containerGroups/cg-enum" + API)
                .then().statusCode(200)
                .body("properties.osType", equalTo("Linux"))
                .body("properties.restartPolicy", equalTo("OnFailure"))
                .body("properties.sku", equalTo("Standard"))
                .body("properties.containers[0].properties.ports[0].protocol", equalTo("TCP"))
                .body("properties.ipAddress.type", equalTo("Public"))
                .body("properties.ipAddress.ports[0].protocol", equalTo("TCP"));
    }

    @Test
    @DisplayName("Omitted ports and resources come back defaulted — the azurerm flatten panics without them")
    void minimalBodyGetsServerDefaults() {
        given().contentType("application/json").body(MINIMAL_BODY)
                .when().put(BASE + "/containerGroups/cg-min" + API)
                .then().statusCode(201)
                .body("properties.osType", equalTo("Linux"))
                .body("properties.restartPolicy", equalTo("Always"))
                .body("properties.containers[0].properties.ports", hasSize(0))
                .body("properties.containers[0].properties.resources.requests.cpu", equalTo(1.0f))
                .body("properties.containers[0].properties.resources.requests.memoryInGB", equalTo(1.5f));
    }

    @Test
    @DisplayName("ipAddress is omitted entirely when not requested")
    void ipAddressOmittedWhenNotRequested() {
        given().contentType("application/json").body(MINIMAL_BODY)
                .when().put(BASE + "/containerGroups/cg-noip" + API)
                .then().statusCode(201)
                .body("properties", not(hasKey("ipAddress")));
    }

    @Test
    @DisplayName("ipAddress gains an ip and a dnsNameLabel-derived fqdn")
    void ipAddressGetsIpAndFqdn() {
        createGroup("cg-ip");
        given().when().get(BASE + "/containerGroups/cg-ip" + API)
                .then().statusCode(200)
                .body("properties.ipAddress.ip", not(emptyOrNullString()))
                .body("properties.ipAddress.fqdn", equalTo("myapp.eastus.azurecontainer.io"));
    }

    @Test
    @DisplayName("Secure environment values and registry passwords are never echoed")
    void secretsAreMasked() {
        createGroup("cg-secret");
        given().when().get(BASE + "/containerGroups/cg-secret" + API)
                .then().statusCode(200)
                .body("properties.containers[0].properties.environmentVariables[0].value", equalTo("visible"))
                .body("properties.containers[0].properties.environmentVariables[1].name", equalTo("SECRET"))
                .body("properties.containers[0].properties.environmentVariables[1]", not(hasKey("secureValue")))
                .body("properties.containers[0].properties.environmentVariables[1]", not(hasKey("value")))
                .body("properties.imageRegistryCredentials[0].server", equalTo("myregistry.azurecr.io"))
                .body("properties.imageRegistryCredentials[0]", not(hasKey("password")));
    }

    @Test
    @DisplayName("Single GET includes instanceView; list responses omit it")
    void instanceViewOnGetOnly() {
        createGroup("cg-iv");
        given().when().get(BASE + "/containerGroups/cg-iv" + API)
                .then().statusCode(200)
                .body("properties.instanceView.state", equalTo("Running"))
                .body("properties.containers[0].properties.instanceView.currentState.state", equalTo("Running"))
                .body("properties.containers[0].properties.instanceView.restartCount", equalTo(0));
        given().when().get(BASE + "/containerGroups" + API)
                .then().statusCode(200)
                .body("value[0].properties", not(hasKey("instanceView")))
                .body("value[0].properties.containers[0].properties", not(hasKey("instanceView")));
    }

    // ── Validation ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT without containers is a 400 InvalidRequestContent")
    void missingContainersIs400() {
        given().contentType("application/json").body("{\"location\": \"eastus\", \"properties\": {}}")
                .when().put(BASE + "/containerGroups/cg-bad" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("InvalidRequestContent"));
    }

    @Test
    @DisplayName("azureFile volumes are rejected with a clear 400")
    void azureFileVolumeIs400() {
        String body = """
                {
                  "location": "eastus",
                  "properties": {
                    "containers": [{"name": "c", "properties": {"image": "busybox"}}],
                    "volumes": [{"name": "share", "azureFile": {"shareName": "s", "storageAccountName": "a"}}]
                  }
                }
                """;
        given().contentType("application/json").body(body)
                .when().put(BASE + "/containerGroups/cg-af" + API)
                .then().statusCode(400)
                .body("error.message", containsString("azureFile"));
    }

    // ── Actions & LRO shapes ───────────────────────────────────────────────────

    @Test
    @DisplayName("start → 202 with Location; restart → 204 with Location; stop → bare 204")
    void actionLroShapes() {
        createGroup("cg-act");
        given().when().post(BASE + "/containerGroups/cg-act/start" + API)
                .then().statusCode(202)
                .header("Location", containsString("/providers/Microsoft.ContainerInstance/locations/"))
                .header("Retry-After", notNullValue());
        given().when().post(BASE + "/containerGroups/cg-act/restart" + API)
                .then().statusCode(204)
                .header("Location", containsString("/operations/"));
        given().when().post(BASE + "/containerGroups/cg-act/stop" + API)
                .then().statusCode(204)
                .header("Location", nullValue());
    }

    @Test
    @DisplayName("Actions on an unknown group return 404")
    void actionOnUnknownGroup404() {
        given().when().post(BASE + "/containerGroups/nope/start" + API)
                .then().statusCode(404);
    }

    @Test
    @DisplayName("The operations endpoint reports terminal Succeeded")
    void operationsEndpointSucceeded() {
        given().when().get(SUB_BASE + "/locations/eastus/operations/" + java.util.UUID.randomUUID() + API)
                .then().statusCode(200)
                .body("status", equalTo("Succeeded"));
    }

    // ── Container sub-resources ────────────────────────────────────────────────

    @Test
    @DisplayName("Logs return the spec's content envelope (empty in mocked mode)")
    void logsMockedEmpty() {
        createGroup("cg-logs");
        given().when().get(BASE + "/containerGroups/cg-logs/containers/web/logs" + API)
                .then().statusCode(200)
                .body("content", equalTo(""));
        given().when().get(BASE + "/containerGroups/cg-logs/containers/nope/logs" + API)
                .then().statusCode(404);
    }

    @Test
    @DisplayName("exec and attach answer an honest 501")
    void execAndAttach501() {
        createGroup("cg-exec");
        given().contentType("application/json").body("{\"command\": \"/bin/sh\", \"terminalSize\": {\"rows\": 24, \"cols\": 80}}")
                .when().post(BASE + "/containerGroups/cg-exec/containers/web/exec" + API)
                .then().statusCode(501)
                .body("error.code", equalTo("NotImplemented"));
        given().when().post(BASE + "/containerGroups/cg-exec/containers/web/attach" + API)
                .then().statusCode(501);
    }

    // ── Location catalogs & misc ───────────────────────────────────────────────

    @Test
    @DisplayName("Location catalogs answer empty lists; outbound deps answer a bare empty array")
    void locationCatalogsAndOutboundDeps() {
        given().when().get(SUB_BASE + "/locations/eastus/cachedImages" + API)
                .then().statusCode(200).body("value", hasSize(0));
        given().when().get(SUB_BASE + "/locations/eastus/capabilities" + API)
                .then().statusCode(200).body("value", hasSize(0));
        given().when().get(SUB_BASE + "/locations/eastus/usages" + API)
                .then().statusCode(200).body("value", hasSize(0));

        createGroup("cg-net");
        given().when().get(BASE + "/containerGroups/cg-net/outboundNetworkDependenciesEndpoints" + API)
                .then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    @DisplayName("Groups appear in the resource-group /resources index")
    void groupAppearsInRgResourceIndex() {
        createGroup("cg-idx");
        given().when().get("/subscriptions/" + SUB + "/resourceGroups/" + RG + "/resources" + API)
                .then().statusCode(200)
                .body("value.find { it.name == 'cg-idx' }.type",
                        equalTo("Microsoft.ContainerInstance/containerGroups"));
    }

    @Test
    @DisplayName("POST /_admin/reset wipes all container groups")
    void adminResetClearsGroups() {
        createGroup("cg-reset");
        given().post("/_admin/reset").then().statusCode(204);
        given().when().get(BASE + "/containerGroups" + API)
                .then().statusCode(200)
                .body("value", hasSize(0));
    }
}
