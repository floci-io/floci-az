package io.floci.az.services.apim;

import io.floci.az.core.StoredObject;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("API Management: services in one store keyed by canonical id")
class ApiManagementResourceStoreTest {

    private static final String API = "?api-version=2022-08-01";
    private static final String BODY = "{\"location\":\"eastus\",\"sku\":{\"name\":\"Developer\",\"capacity\":1},"
        + "\"properties\":{\"publisherEmail\":\"a@b.c\",\"publisherName\":\"x\"}}";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String service(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.ApiManagement/service/" + name;
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneService() {
        given().contentType("application/json").body(BODY).when().put(service("sub-case", "RG-Case", "Apim1") + API)
            .then().statusCode(200);
        given().contentType("application/json")
            .body("{\"location\":\"westus\",\"properties\":{\"publisherEmail\":\"b@c.d\",\"publisherName\":\"y\"}}")
            .when().put(service("sub-case", "rg-case", "apim1") + API)
            .then().statusCode(200)
            .body("id", equalTo(service("sub-case", "RG-Case", "Apim1")))
            .body("name", equalTo("Apim1"))
            .body("location", equalTo("westus"));

        given().when().get(service("sub-case", "rg-case", "APIM1") + API).then().statusCode(200)
            .body("properties.publisherEmail", equalTo("b@c.d"));
        given().when().get("/subscriptions/sub-case/resourceGroups/rg-case/providers/Microsoft.ApiManagement/service"
                + API)
            .then().statusCode(200).body("value", hasSize(1));
    }

    @Test
    void childrenUnderAnotherCasingOfTheServiceReachTheSameChildren() {
        given().contentType("application/json").body(BODY).when().put(service("sub-kids", "RG-Kids", "Apim2") + API)
            .then().statusCode(200);
        given().contentType("application/json").body("{\"properties\":{\"path\":\"echo\"}}")
            .when().put(service("sub-kids", "rg-kids", "apim2") + "/apis/echo" + API)
            .then().statusCode(200)
            .body("id", equalTo(service("sub-kids", "RG-Kids", "Apim2") + "/apis/echo"));

        given().when().get(service("sub-kids", "RG-Kids", "Apim2") + "/apis" + API).then().statusCode(200)
            .body("value", hasSize(1));

        given().when().delete(service("sub-kids", "RG-KIDS", "APIM2") + API).then().statusCode(200);
        given().when().get(service("sub-kids", "RG-Kids", "Apim2") + API).then().statusCode(404);
        given().when().get(service("sub-kids", "RG-Kids", "Apim2") + "/apis/echo" + API).then().statusCode(404);
    }

    @Test
    void aNameTakenInAnotherScopeIsRefusedWithTheServicesOwnConflict() {
        given().contentType("application/json").body(BODY).when().put(service("sub-one", "rg-one", "Apim4") + API)
            .then().statusCode(200);

        for (String other : new String[] {service("sub-one", "rg-two", "apim4"), service("sub-two", "rg-one", "APIM4")}) {
            given().contentType("application/json").body(BODY).when().put(other + API)
                .then().statusCode(409)
                .body("error.code", equalTo("ServiceAlreadyExists"))
                .body("error.message", equalTo("Api service already exists: " + other.substring(other.lastIndexOf('/') + 1)));
            given().when().delete(other + API).then().statusCode(200);
        }
        given().when().get(service("sub-one", "rg-one", "Apim4") + API).then().statusCode(200);
    }

    @Test
    void aServiceIsStoredUnderItsCanonicalKeyInTheApimBackend() {
        given().contentType("application/json").body(BODY).when().put(service("Sub-Store", "RG-Store", "Apim3") + API)
            .then().statusCode(200);

        StorageBackend<String, StoredObject> backend = storageFactory.create("apim");
        assertTrue(backend.keys().contains(service("Sub-Store", "RG-Store", "Apim3").toLowerCase()));
        assertEquals(1, backend.keys().size());

        given().when().delete(service("Sub-Store", "RG-Store", "Apim3") + API).then().statusCode(200);
        assertTrue(backend.keys().isEmpty());
    }
}
