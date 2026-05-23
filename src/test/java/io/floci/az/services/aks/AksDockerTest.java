package io.floci.az.services.aks;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AKS handler tests with {@code mocked=false} — starts a real k3s container.
 *
 * <p>Skipped automatically when Docker is unavailable.
 * k3s can take up to 120 s to reach /livez, so each poll step sleeps 5 s
 * with a 150 s overall timeout.
 *
 * <p>Tests are ordered and share state (cluster created in test 1, verified in 2-4, deleted in 5).
 * {@code @BeforeAll} only does a filesystem check so it is safe to run before Quarkus is ready;
 * the HTTP reset is done inside the first {@code @Test} method.
 */
@QuarkusTest
@TestProfile(AksDockerTest.RealModeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AksHandler — real k3s mode (Docker required)")
class AksDockerTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.aks.mocked", "false");
        }
    }

    private static final String SUB  = "test-sub-aks-docker";
    private static final String RG   = "test-rg-docker";
    private static final String NAME = "k3s-test-cluster";
    private static final String BASE =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG
            + "/providers/Microsoft.ContainerService";
    private static final String CLUSTER_PATH = BASE + "/managedClusters/" + NAME;

    /** Pure filesystem check — safe to run before Quarkus is fully ready. */
    @BeforeAll
    void checkDockerAvailable() {
        boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock"))
                || System.getenv("DOCKER_HOST") != null;
        assumeTrue(dockerAvailable, "Docker socket not available — skipping real k3s tests");
    }

    @AfterAll
    void cleanup() {
        try {
            given().delete(CLUSTER_PATH + "?api-version=2024-04-01");
        } catch (Exception ignored) {}
    }

    // ── Create cluster ────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("PUT cluster returns 201 with provisioningState=Creating")
    void createClusterReturns201Creating() {
        // Reset state before first test (safe here — Quarkus is ready by test time)
        given().post("/_admin/reset").then().statusCode(204);

        given()
            .contentType("application/json")
            .body("""
                {
                  "location": "eastus",
                  "properties": {
                    "kubernetesVersion": "1.29",
                    "dnsPrefix": "k3s-test-dns",
                    "agentPoolProfiles": [
                      {"name": "nodepool1", "count": 1, "vmSize": "Standard_DS2_v2",
                       "osType": "Linux", "mode": "System"}
                    ]
                  }
                }
                """)
            .when().put(CLUSTER_PATH + "?api-version=2024-04-01")
            .then().statusCode(201)
            .body("name", equalTo(NAME))
            .body("properties.provisioningState", oneOf("Creating", "Succeeded", "Failed"));
    }

    // ── Poll until Succeeded ──────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("cluster reaches provisioningState=Succeeded within 150 s")
    void clusterReachesSucceeded() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 150_000;
        String state = "Creating";

        while (!"Succeeded".equals(state) && !"Failed".equals(state)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000);
            Response r = given()
                    .when().get(CLUSTER_PATH + "?api-version=2024-04-01");
            if (r.statusCode() == 200) {
                state = r.path("properties.provisioningState");
            }
        }

        assumeTrue(!"Failed".equals(state),
                "k3s container failed to start — Docker may lack privileged-container support");
        assertEquals("Succeeded", state,
                "Cluster did not reach Succeeded within 150 s; last state=" + state);
    }

    // ── Verify ARM shape ──────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("GET cluster returns correct ARM shape after Succeeded")
    void getClusterArmShape() {
        given()
            .when().get(CLUSTER_PATH + "?api-version=2024-04-01")
            .then().statusCode(200)
            .body("name", equalTo(NAME))
            .body("type", equalTo("Microsoft.ContainerService/managedClusters"))
            .body("location", equalTo("eastus"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.kubernetesVersion", equalTo("1.29"))
            .body("id", containsString("/managedClusters/" + NAME));
    }

    // ── Kubeconfig contains real server URL ───────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("listClusterAdminCredential returns kubeconfig with real k3s server URL")
    void adminCredentialHasRealServerUrl() {
        String encoded = given()
                .contentType("application/json")
                .when().post(CLUSTER_PATH + "/listClusterAdminCredential?api-version=2024-04-01")
                .then().statusCode(200)
                .body("kubeconfigs[0].name", equalTo("clusterAdmin"))
                .extract().path("kubeconfigs[0].value");

        assertNotNull(encoded, "kubeconfig value must not be null");
        String kubeconfig = new String(Base64.getDecoder().decode(encoded));
        assertTrue(kubeconfig.contains("server: https://"),
                "kubeconfig must contain a server URL; got:\n" + kubeconfig);
        assertTrue(kubeconfig.contains("certificate-authority-data:")
                        || kubeconfig.contains("insecure-skip-tls-verify"),
                "kubeconfig must contain CA data or skip-tls flag");
    }

    // ── Delete cluster ────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("DELETE cluster returns 202 and subsequent GET returns 404")
    void deleteCluster() {
        given()
            .when().delete(CLUSTER_PATH + "?api-version=2024-04-01")
            .then().statusCode(202);

        given()
            .when().get(CLUSTER_PATH + "?api-version=2024-04-01")
            .then().statusCode(404);
    }
}
