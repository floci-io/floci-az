package io.floci.az.services.acr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.acr.AcrModels.Registry;
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
@TestProfile(AcrHandlerTest.MockedProfile.class)
@DisplayName("ACR: one store keyed by canonical id, and the global name registry")
class AcrGlobalNameTest {

    private static final String API = "?api-version=2023-07-01";
    private static final String BODY = "{\"location\":\"eastus\",\"sku\":{\"name\":\"Basic\"}}";

    @Inject
    StorageFactory storageFactory;

    @Inject
    GlobalNameRegistry names;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String registry(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.ContainerRegistry/registries/"
            + name;
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneRegistry() {
        given().contentType("application/json").body(BODY).when().put(registry("sub-case", "RG-Case", "casereg") + API)
            .then().statusCode(201);
        given().contentType("application/json").body(BODY).when().put(registry("sub-case", "rg-case", "casereg") + API)
            .then().statusCode(200)
            .body("id", equalTo(registry("sub-case", "RG-Case", "casereg")));

        given().when().get("/subscriptions/sub-case/resourceGroups/rg-case/providers/Microsoft.ContainerRegistry"
                + "/registries" + API)
            .then().statusCode(200).body("value", hasSize(1));
    }

    @Test
    void checkNameAvailabilityAnswersFromTheRegistry() {
        String check = "/subscriptions/sub-check/providers/Microsoft.ContainerRegistry/checkNameAvailability" + API;
        String request = "{\"name\":\"checkreg\",\"type\":\"Microsoft.ContainerRegistry/registries\"}";

        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(true));
        given().contentType("application/json").body(BODY).when().put(registry("sub-owner", "rg", "checkreg") + API)
            .then().statusCode(201);
        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(false)).body("reason", equalTo("AlreadyExists"));
        given().when().delete(registry("sub-owner", "rg", "checkreg") + API).then().statusCode(202);
        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(true));
    }

    @Test
    void aRegistryStoredUnderTheOldKeyIsServedOwnsItsNameAndMovesOnItsFirstWrite() throws Exception {
        Registry legacy = new Registry();
        legacy.setInstanceId("abcd1234");
        legacy.setSubscriptionId("Sub-Legacy");
        legacy.setResourceGroup("RG-Legacy");
        legacy.setName("legacyreg");
        legacy.setLocation("eastus");
        legacy.setSkuName("Basic");
        legacy.setLoginServer("legacyreg.azurecr.io");
        legacy.setProvisioningState("Succeeded");
        legacy.setCreatedAt(Instant.now());
        StorageBackend<String, StoredObject> backend = storageFactory.create("acr");
        String oldKey = "Sub-Legacy/RG-Legacy/legacyreg";
        backend.put(oldKey, new StoredObject(oldKey,
            new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsBytes(legacy), Map.of(), Instant.now(),
            oldKey));
        names.clear();

        given().when().get(registry("sub-legacy", "rg-legacy", "legacyreg") + API).then().statusCode(200)
            .body("name", equalTo("legacyreg"));
        given().contentType("application/json").body(BODY).when().put(registry("sub-other", "rg", "legacyreg") + API)
            .then().statusCode(409).body("error.code", equalTo("AlreadyInUse"));

        given().contentType("application/json").body("{\"tags\":{\"env\":\"t\"}}")
            .when().patch(registry("Sub-Legacy", "RG-Legacy", "legacyreg") + API).then().statusCode(200);
        assertFalse(backend.get(oldKey).isPresent(), "the write moved the entry off its old key");
        assertTrue(backend.keys().stream()
            .anyMatch(key -> key.equals(registry("sub-legacy", "rg-legacy", "legacyreg").toLowerCase())));
    }
}
