package io.floci.az.services.aks;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Credential listing in real (k3s) mode with the container manager mocked out, so a cluster can be
 * held in {@code Creating} or forced to {@code Failed} without Docker.
 */
@QuarkusTest
@TestProfile(AksHandlerRealModeCredentialsTest.RealModeProfile.class)
@DisplayName("AksHandler: credentials of a cluster that is not running (real mode)")
@SuppressWarnings("unused")
class AksHandlerRealModeCredentialsTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.aks.mocked", "false");
        }
    }

    private static final String BASE =
            "/subscriptions/test-sub-aks-real/resourceGroups/test-rg-aks-real"
            + "/providers/Microsoft.ContainerService/managedClusters/";

    @InjectMock
    AksClusterManager clusterManager;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private void createCluster(String name, String expectedState) {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{}}")
            .put(BASE + name + "?api-version=2025-10-01")
            .then().statusCode(201)
            .body("properties.provisioningState", equalTo(expectedState));
    }

    @Test
    @DisplayName("listClusterAdminCredential on a Creating cluster is rejected instead of returning a synthetic kubeconfig")
    void adminCredentialsOfCreatingClusterAreRejected() {
        createCluster("creating-cluster", "Creating");

        given()
            .contentType("application/json")
            .when().post(BASE + "creating-cluster/listClusterAdminCredential?api-version=2025-10-01")
            .then().statusCode(409)
            .body("error.code", equalTo("OperationNotAllowed"))
            .body("error.message", containsString("Creating"))
            .body("kubeconfigs", nullValue());
    }

    @Test
    @DisplayName("listClusterUserCredential on a Creating cluster is rejected")
    void userCredentialsOfCreatingClusterAreRejected() {
        createCluster("creating-user-cluster", "Creating");

        given()
            .contentType("application/json")
            .when().post(BASE + "creating-user-cluster/listClusterUserCredential?api-version=2025-10-01")
            .then().statusCode(409)
            .body("error.code", equalTo("OperationNotAllowed"));
    }

    @Test
    @DisplayName("listClusterAdminCredential on a Failed cluster is rejected")
    void adminCredentialsOfFailedClusterAreRejected() {
        doThrow(new IllegalStateException("docker unavailable")).when(clusterManager).startCluster(any());
        createCluster("failed-cluster", "Failed");

        given()
            .contentType("application/json")
            .when().post(BASE + "failed-cluster/listClusterAdminCredential?api-version=2025-10-01")
            .then().statusCode(409)
            .body("error.code", equalTo("OperationNotAllowed"))
            .body("error.message", containsString("Failed"));
    }

    @Test
    @DisplayName("listing credentials of an unknown cluster still returns 404")
    void credentialsOfUnknownClusterReturn404() {
        given()
            .contentType("application/json")
            .when().post(BASE + "no-such-cluster/listClusterAdminCredential?api-version=2025-10-01")
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
    }
}
