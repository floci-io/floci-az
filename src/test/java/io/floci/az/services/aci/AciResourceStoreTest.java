package io.floci.az.services.aci;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.aci.AciModels.ContainerGroup;
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
@TestProfile(AciHandlerTest.MockedProfile.class)
@DisplayName("ACI: one store keyed by canonical id")
class AciResourceStoreTest {

    private static final String API = "?api-version=2023-05-01";
    private static final String BODY = "{\"location\":\"eastus\",\"properties\":{\"osType\":\"Linux\",\"containers\":[{\"name\":\"web\",\"properties\":{\"image\":\"nginx:1.27\",\"resources\":{\"requests\":{\"cpu\":1,\"memoryInGB\":1}}}}]}}";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String group(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg
            + "/providers/Microsoft.ContainerInstance/containerGroups/" + name;
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneGroup() {
        given().contentType("application/json").body(BODY).when().put(group("sub-case", "RG-Case", "Group1") + API)
            .then().statusCode(201);
        given().contentType("application/json").body(BODY).when().put(group("sub-case", "rg-case", "group1") + API)
            .then().statusCode(200)
            .body("id", equalTo(group("sub-case", "RG-Case", "Group1")));

        given().when().get("/subscriptions/sub-case/resourceGroups/rg-case/providers/Microsoft.ContainerInstance"
                + "/containerGroups" + API)
            .then().statusCode(200).body("value", hasSize(1));
    }

    @Test
    void aGroupStoredUnderTheOldKeyIsServedAndMovesOnItsFirstWrite() throws Exception {
        ContainerGroup legacy = new ContainerGroup();
        legacy.setSubscriptionId("Sub-Legacy");
        legacy.setResourceGroup("RG-Legacy");
        legacy.setName("legacygroup");
        legacy.setLocation("eastus");
        legacy.setProvisioningState("Succeeded");
        legacy.setTimeCreated(Instant.now());
        legacy.setProperties(Map.of("osType", "Linux"));
        StorageBackend<String, StoredObject> backend = storageFactory.create("aci");
        String oldKey = "Sub-Legacy/RG-Legacy/legacygroup";
        backend.put(oldKey, new StoredObject(oldKey,
            new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsBytes(legacy), Map.of(), Instant.now(),
            oldKey));

        given().when().get(group("sub-legacy", "rg-legacy", "legacygroup") + API).then().statusCode(200)
            .body("name", equalTo("legacygroup"));
        given().when().get("/subscriptions/Sub-Legacy/providers/Microsoft.ContainerInstance/containerGroups" + API)
            .then().statusCode(200).body("value", hasSize(1));

        given().contentType("application/json").body("{\"tags\":{\"env\":\"t\"}}")
            .when().patch(group("Sub-Legacy", "RG-Legacy", "legacygroup") + API).then().statusCode(200);
        assertFalse(backend.get(oldKey).isPresent(), "the write moved the entry off its old key");
        assertTrue(backend.keys().stream()
            .anyMatch(key -> key.equals(group("sub-legacy", "rg-legacy", "legacygroup").toLowerCase())));
    }
}
