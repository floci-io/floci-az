package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(AksHandlerTest.MockedProfile.class)
@DisplayName("AKS: one store keyed by canonical id")
class AksResourceStoreTest {

    private static final String API = "?api-version=2024-02-01";
    private static final String BODY = "{\"location\":\"eastus\",\"properties\":{\"dnsPrefix\":\"dns\"}}";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String cluster(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.ContainerService/managedClusters/"
            + name;
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneCluster() {
        given().contentType("application/json").body(BODY).when().put(cluster("sub-case", "RG-Case", "Cluster1") + API)
            .then().statusCode(201);
        given().contentType("application/json").body(BODY).when().put(cluster("sub-case", "rg-case", "cluster1") + API)
            .then().statusCode(200)
            .body("id", equalTo(cluster("sub-case", "RG-Case", "Cluster1")));

        given().when().get("/subscriptions/sub-case/resourceGroups/rg-case/providers/Microsoft.ContainerService"
                + "/managedClusters" + API)
            .then().statusCode(200).body("value", hasSize(1));
    }

    @Test
    void aClusterStoredUnderTheOldKeyIsServedAndMovesOnItsFirstWrite() throws Exception {
        ManagedCluster legacy = new ManagedCluster();
        legacy.setInstanceId("abcd1234");
        legacy.setSubscriptionId("Sub-Legacy");
        legacy.setResourceGroup("RG-Legacy");
        legacy.setName("legacycluster");
        legacy.setLocation("eastus");
        legacy.setKubernetesVersion("1.29");
        legacy.setProvisioningState("Succeeded");
        legacy.setCreatedAt(Instant.now());
        StorageBackend<String, StoredObject> backend = storageFactory.create("aks");
        String oldKey = "Sub-Legacy/RG-Legacy/legacycluster";
        backend.put(oldKey, new StoredObject(oldKey,
            new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsBytes(legacy), Map.of(), Instant.now(),
            oldKey));

        given().when().get(cluster("sub-legacy", "rg-legacy", "legacycluster") + API).then().statusCode(200)
            .body("name", equalTo("legacycluster"));
        given().when().get("/subscriptions/Sub-Legacy/providers/Microsoft.ContainerService/managedClusters" + API)
            .then().statusCode(200).body("value", hasSize(1));

        given().contentType("application/json").body("{\"tags\":{\"env\":\"t\"}}")
            .when().patch(cluster("Sub-Legacy", "RG-Legacy", "legacycluster") + API).then().statusCode(200);
        assertFalse(backend.get(oldKey).isPresent(), "the write moved the entry off its old key");
        assertTrue(backend.keys().stream()
            .anyMatch(key -> key.equals(cluster("sub-legacy", "rg-legacy", "legacycluster").toLowerCase())));
    }
}
