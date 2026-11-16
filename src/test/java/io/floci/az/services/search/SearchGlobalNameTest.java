package io.floci.az.services.search;

import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@DisplayName("Search: one store keyed by canonical id, and the global name registry")
class SearchGlobalNameTest {

    private static final String API = "?api-version=2025-05-01";
    private static final String BODY = "{\"location\":\"eastus\",\"sku\":{\"name\":\"basic\"}}";

    @Inject
    StorageFactory storageFactory;

    @Inject
    GlobalNameRegistry names;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String service(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Search/searchServices/" + name;
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneServiceAndKeepsItsId() {
        given().contentType("application/json").body(BODY).when().put(service("sub-case", "RG-Case", "casesearch") + API)
            .then().statusCode(201);
        given().contentType("application/json").body(BODY)
            .when().put("/subscriptions/sub-case/resourcegroups/rg-case/providers/microsoft.search/searchservices/"
                + "casesearch" + API)
            .then().statusCode(200)
            .body("id", equalTo(service("sub-case", "RG-Case", "casesearch")));
        given().when().get("/subscriptions/sub-case/resourceGroups/rg-case/providers/Microsoft.Search/searchServices"
                + API)
            .then().statusCode(200).body("value", hasSize(1));

        StorageBackend<String, StoredObject> backend = storageFactory.create("search");
        assertEquals(1, backend.keys().size(), "one resource, stored once under its canonical key");
    }

    @Test
    void aNameBelongsToTheFirstServiceUntilItIsDeleted() {
        given().contentType("application/json").body(BODY).when().put(service("sub-owner", "rg", "ownedsearch") + API)
            .then().statusCode(201);

        given().contentType("application/json").body(BODY).when().put(service("sub-other", "rg", "ownedsearch") + API)
            .then().statusCode(409).body("error.code", equalTo("Conflict"));
        given().contentType("application/json").body(BODY).when().put(service("sub-owner", "rg-two", "ownedsearch") + API)
            .then().statusCode(409).body("error.code", equalTo("Conflict"));

        given().when().delete(service("sub-owner", "rg", "ownedsearch") + API).then().statusCode(200);
        given().contentType("application/json").body(BODY).when().put(service("sub-other", "rg", "ownedsearch") + API)
            .then().statusCode(201);
    }

    @Test
    void aStoredServiceOwnsItsNameAfterTheRegistryIsRebuilt() {
        given().contentType("application/json").body(BODY).when().put(service("sub-owner", "rg", "keptsearch") + API)
            .then().statusCode(201);

        // What a restart does: the registry holds nothing and rebuilds itself from every service's stored names.
        names.clear();

        given().contentType("application/json").body(BODY).when().put(service("sub-other", "rg", "keptsearch") + API)
            .then().statusCode(409).body("error.code", equalTo("Conflict"));
    }
}
