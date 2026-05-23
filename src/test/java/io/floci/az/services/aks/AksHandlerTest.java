package io.floci.az.services.aks;

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
 * Quarkus-level tests for {@link AksHandler}.
 *
 * <p>Always runs in mocked mode (no k3s container) regardless of the default in application.yml.
 * Covers ARM path routing, CRUD, credentials, and agent pools.
 */
@QuarkusTest
@TestProfile(AksHandlerTest.MockedProfile.class)
@DisplayName("AksHandler — routing and CRUD (mocked mode)")
@SuppressWarnings("unused")
class AksHandlerTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.aks.mocked", "true");
        }
    }

    private static final String SUB  = "test-sub-aks";
    private static final String RG   = "test-rg-aks";
    private static final String BASE =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG
            + "/providers/Microsoft.ContainerService";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    // ── GET unknown cluster → 404 ─────────────────────────────────────────────

    @Test
    @DisplayName("GET unknown cluster returns 404")
    void getUnknownClusterReturns404() {
        given()
            .when().get(BASE + "/managedClusters/no-such-cluster?api-version=2024-04-01")
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
    }

    // ── CREATE cluster (mocked → 201, provisioningState=Succeeded) ───────────

    @Test
    @DisplayName("PUT cluster creates cluster and returns 201 with Succeeded state")
    void createClusterReturns201() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "location": "eastus",
                  "properties": {
                    "kubernetesVersion": "1.29",
                    "dnsPrefix": "my-aks-dns",
                    "agentPoolProfiles": [
                      {"name": "nodepool1", "count": 2, "vmSize": "Standard_DS2_v2",
                       "osType": "Linux", "mode": "System"}
                    ]
                  }
                }
                """)
            .when().put(BASE + "/managedClusters/my-cluster?api-version=2024-04-01")
            .then().statusCode(201)
            .body("name", equalTo("my-cluster"))
            .body("type", equalTo("Microsoft.ContainerService/managedClusters"))
            .body("location", equalTo("eastus"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.kubernetesVersion", equalTo("1.29"))
            .body("properties.dnsPrefix", equalTo("my-aks-dns"))
            .body("properties.agentPoolProfiles", hasSize(1))
            .body("properties.agentPoolProfiles[0].name", equalTo("nodepool1"))
            .body("properties.agentPoolProfiles[0].count", equalTo(2))
            .body("id", containsString("/managedClusters/my-cluster"));
    }

    // ── GET cluster after create ───────────────────────────────────────────────

    @Test
    @DisplayName("GET cluster after create returns 200")
    void getClusterAfterCreate() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"westus\",\"properties\":{\"kubernetesVersion\":\"1.30\"}}")
            .put(BASE + "/managedClusters/get-test?api-version=2024-04-01")
            .then().statusCode(201);

        given()
            .when().get(BASE + "/managedClusters/get-test?api-version=2024-04-01")
            .then().statusCode(200)
            .body("name", equalTo("get-test"))
            .body("location", equalTo("westus"))
            .body("properties.kubernetesVersion", equalTo("1.30"))
            .body("properties.provisioningState", equalTo("Succeeded"));
    }

    // ── DELETE cluster ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE cluster returns 202 and subsequent GET returns 404")
    void deleteClusterReturns202() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{}}")
            .put(BASE + "/managedClusters/del-cluster?api-version=2024-04-01")
            .then().statusCode(201);

        given()
            .when().delete(BASE + "/managedClusters/del-cluster?api-version=2024-04-01")
            .then().statusCode(202);

        given()
            .when().get(BASE + "/managedClusters/del-cluster?api-version=2024-04-01")
            .then().statusCode(404);
    }

    // ── LIST by resource group ─────────────────────────────────────────────────

    @Test
    @DisplayName("LIST clusters by resource group returns value array")
    void listByResourceGroup() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{}}")
            .put(BASE + "/managedClusters/list-a?api-version=2024-04-01")
            .then().statusCode(201);

        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{}}")
            .put(BASE + "/managedClusters/list-b?api-version=2024-04-01")
            .then().statusCode(201);

        given()
            .when().get(BASE + "/managedClusters?api-version=2024-04-01")
            .then().statusCode(200)
            .body("value", hasSize(greaterThanOrEqualTo(2)));
    }

    // ── LIST by subscription ───────────────────────────────────────────────────

    @Test
    @DisplayName("LIST clusters by subscription returns value array")
    void listBySubscription() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{}}")
            .put(BASE + "/managedClusters/sub-cluster?api-version=2024-04-01")
            .then().statusCode(201);

        given()
            .when().get("/subscriptions/" + SUB
                        + "/providers/Microsoft.ContainerService/managedClusters?api-version=2024-04-01")
            .then().statusCode(200)
            .body("value", hasSize(greaterThanOrEqualTo(1)));
    }

    // ── PATCH tags ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH tags updates cluster tags")
    void patchTags() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{}}")
            .put(BASE + "/managedClusters/tag-cluster?api-version=2024-04-01")
            .then().statusCode(201);

        given()
            .contentType("application/json")
            .body("{\"tags\":{\"env\":\"test\"}}")
            .when().patch(BASE + "/managedClusters/tag-cluster?api-version=2024-04-01")
            .then().statusCode(200)
            .body("tags.env", equalTo("test"));
    }

    // ── listClusterAdminCredential ─────────────────────────────────────────────

    @Test
    @DisplayName("listClusterAdminCredential returns base64 kubeconfig")
    void listAdminCredential() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{}}")
            .put(BASE + "/managedClusters/cred-cluster?api-version=2024-04-01")
            .then().statusCode(201);

        given()
            .contentType("application/json")
            .when().post(BASE + "/managedClusters/cred-cluster/listClusterAdminCredential"
                         + "?api-version=2024-04-01")
            .then().statusCode(200)
            .body("kubeconfigs", hasSize(1))
            .body("kubeconfigs[0].name", equalTo("clusterAdmin"))
            .body("kubeconfigs[0].value", notNullValue());
    }

    // ── Agent pool list ────────────────────────────────────────────────────────

    @Test
    @DisplayName("LIST agentPools returns value array")
    void listAgentPools() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "location": "eastus",
                  "properties": {
                    "agentPoolProfiles": [
                      {"name": "pool1", "count": 1, "vmSize": "Standard_DS2_v2",
                       "osType": "Linux", "mode": "System"}
                    ]
                  }
                }
                """)
            .put(BASE + "/managedClusters/pool-cluster?api-version=2024-04-01")
            .then().statusCode(201);

        given()
            .when().get(BASE + "/managedClusters/pool-cluster/agentPools?api-version=2024-04-01")
            .then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].name", equalTo("pool1"));
    }

    // ── Agent pool GET ────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET agentPool returns pool details")
    void getAgentPool() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "location": "eastus",
                  "properties": {
                    "agentPoolProfiles": [
                      {"name": "sys", "count": 3, "vmSize": "Standard_D4s_v3",
                       "osType": "Linux", "mode": "System"}
                    ]
                  }
                }
                """)
            .put(BASE + "/managedClusters/ap-get-cluster?api-version=2024-04-01")
            .then().statusCode(201);

        given()
            .when().get(BASE + "/managedClusters/ap-get-cluster/agentPools/sys?api-version=2024-04-01")
            .then().statusCode(200)
            .body("name", equalTo("sys"))
            .body("properties.count", equalTo(3))
            .body("properties.vmSize", equalTo("Standard_D4s_v3"));
    }
}
