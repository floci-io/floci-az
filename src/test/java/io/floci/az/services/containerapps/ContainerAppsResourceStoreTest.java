package io.floci.az.services.containerapps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.containerapps.ContainerAppsModels.ContainerAppState;
import io.floci.az.services.containerapps.ContainerAppsModels.ManagedEnvironmentState;
import io.floci.az.services.containerapps.ContainerAppsModels.RevisionState;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(ContainerAppsHandlerTest.MockedProfile.class)
@DisplayName("Container Apps: environments and apps in one backend, each in its own store")
class ContainerAppsResourceStoreTest {

    private static final String API = "?api-version=2025-07-01";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String PROVIDER = "/subscriptions/Sub-Legacy/resourceGroups/RG-Legacy/providers/Microsoft.App";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static void putLegacy(StorageBackend<String, StoredObject> backend, String key, Object value)
            throws Exception {
        backend.put(key, new StoredObject(key, MAPPER.writeValueAsBytes(value), Map.of(), Instant.now(), "etag"));
    }

    @Test
    void entriesStoredUnderTheOldKeysAreServedPerTypeAndMoveOnTheirFirstWrite() throws Exception {
        ObjectNode environmentDocument = MAPPER.createObjectNode().put("location", "eastus");
        ManagedEnvironmentState environment = new ManagedEnvironmentState();
        environment.setSubscriptionId("Sub-Legacy");
        environment.setResourceGroup("RG-Legacy");
        environment.setName("legacy-env");
        environment.setDocument(environmentDocument);
        environment.setDefaultDomain("legacy-env.local.azurecontainerapps.io");
        environment.setCreatedAt(Instant.now());
        ContainerAppState app = new ContainerAppState();
        app.setSubscriptionId("Sub-Legacy");
        app.setResourceGroup("RG-Legacy");
        app.setName("legacy-app");
        ObjectNode appDocument = MAPPER.createObjectNode().put("location", "eastus");
        appDocument.putObject("properties")
            .put("managedEnvironmentId", PROVIDER + "/managedEnvironments/legacy-env")
            .put("environmentId", PROVIDER + "/managedEnvironments/legacy-env");
        ObjectNode template = MAPPER.createObjectNode();
        template.putArray("containers").addObject().put("name", "web").put("image", "nginx:1.27");
        ((ObjectNode) appDocument.get("properties")).set("template", template);
        app.setDocument(appDocument);
        app.setCreatedAt(Instant.now());
        RevisionState revision = new RevisionState();
        revision.setName("legacy-app--r1");
        revision.setTemplate(template);
        revision.setActive(true);
        revision.setReplicas(1);
        revision.setProvisioningState("Provisioned");
        revision.setRunningState("Running");
        revision.setHealthState("Healthy");
        revision.setCreatedTime(Instant.now());
        app.setRevisions(List.of(revision));
        StorageBackend<String, StoredObject> backend = storageFactory.create("containerapps");
        putLegacy(backend, "environment/sub-legacy/rg-legacy/legacy-env", environment);
        putLegacy(backend, "app/sub-legacy/rg-legacy/legacy-app", app);

        given().when().get(PROVIDER + "/managedEnvironments/legacy-env" + API).then().statusCode(200)
            .body("name", equalTo("legacy-env"));
        given().when().get(PROVIDER + "/managedEnvironments" + API).then().statusCode(200)
            .body("value", hasSize(1));
        given().when().get(PROVIDER + "/containerApps" + API).then().statusCode(200)
            .body("value", hasSize(1)).body("value[0].name", equalTo("legacy-app"));
        given().when().delete(PROVIDER + "/managedEnvironments/legacy-env" + API).then().statusCode(409)
            .body("error.code", equalTo("ManagedEnvironmentInUse"));

        given().contentType("application/json").body("{\"tags\":{\"env\":\"t\"}}")
            .when().patch(PROVIDER + "/managedEnvironments/legacy-env" + API).then().statusCode(200);
        assertFalse(backend.get("environment/sub-legacy/rg-legacy/legacy-env").isPresent(),
            "the write moved the environment off its old key");
        assertTrue(backend.keys().contains((PROVIDER + "/managedEnvironments/legacy-env").toLowerCase()));
        assertTrue(backend.get("app/sub-legacy/rg-legacy/legacy-app").isPresent(), "the app was not written");
        given().when().get(PROVIDER + "/containerApps" + API).then().statusCode(200).body("value", hasSize(1));
        given().when().get(PROVIDER + "/managedEnvironments" + API).then().statusCode(200)
            .body("value", hasSize(1));

        given().contentType("application/json").body("{\"tags\":{\"env\":\"t\"}}")
            .when().patch(PROVIDER + "/containerApps/legacy-app" + API).then().statusCode(200);
        assertFalse(backend.get("app/sub-legacy/rg-legacy/legacy-app").isPresent(),
            "the write moved the app off its old key");
        assertTrue(backend.keys().contains((PROVIDER + "/containerApps/legacy-app").toLowerCase()));
        given().when().get(PROVIDER + "/containerApps/legacy-app/revisions" + API).then().statusCode(200)
            .body("value", hasSize(1)).body("value[0].name", equalTo("legacy-app--r1"));
        given().when().get(PROVIDER + "/containerApps" + API).then().statusCode(200).body("value", hasSize(1));
    }
}
