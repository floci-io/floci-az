package io.floci.az.services.apim;

import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @ParameterizedTest
    @ValueSource(strings = {"Microsoft.ApiManagement/checkNameAvailability", "microsoft.apimanagement/checknameavailability"})
    void checkNameAvailabilityAnswersFromTheRegistryInEverySubscription(String operation) {
        String request = "{\"name\":\"checkapim\"}";
        String inOwner = "/subscriptions/sub-check-a/providers/" + operation + API;
        String inOther = "/subscriptions/sub-check-b/providers/" + operation + API;

        given().contentType("application/json").body(request).when().post(inOther).then().statusCode(200)
            .body("nameAvailable", equalTo(true)).body("reason", equalTo("Valid"));
        given().contentType("application/json").body(BODY).when().put(service("sub-check-a", "rg", "CheckApim") + API)
            .then().statusCode(200);
        for (String check : new String[] {inOwner, inOther}) {
            given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
                .body("nameAvailable", equalTo(false)).body("reason", equalTo("AlreadyExists"));
        }
        given().when().delete(service("sub-check-a", "rg", "CheckApim") + API).then().statusCode(200);
        given().contentType("application/json").body(request).when().post(inOther).then().statusCode(200)
            .body("nameAvailable", equalTo(true));
    }

    @Test
    void checkNameAvailabilityRejectsANameOutsideTheServiceNameRule() {
        given().contentType("application/json").body("{\"name\":\"1-starts-with-a-digit\"}")
            .when().post("/subscriptions/sub-invalid/providers/Microsoft.ApiManagement/checkNameAvailability" + API)
            .then().statusCode(200)
            .body("nameAvailable", equalTo(false)).body("reason", equalTo("Invalid"));
    }

    @Test
    void theSubscriptionWideListHoldsServicesOfEveryGroup() {
        given().contentType("application/json").body(BODY).when().put(service("sub-all", "rg-a", "apim-a") + API)
            .then().statusCode(200);
        given().contentType("application/json").body(BODY).when().put(service("sub-all", "rg-b", "apim-b") + API)
            .then().statusCode(200);
        given().when().get("/subscriptions/sub-all/providers/Microsoft.ApiManagement/service" + API)
            .then().statusCode(200).body("value", hasSize(2));
    }

    @Test
    void aChildPutThroughOtherCasingUpdatesTheOneChildAndTheGatewayFindsIt() {
        String service = service("sub-case", "RG-Children", "ApimKids");
        given().contentType("application/json").body(BODY).when().put(service + API).then().statusCode(200);
        String apiBody = "{\"properties\":{\"displayName\":\"%s\",\"path\":\"kids\","
            + "\"protocols\":[\"https\"],\"serviceUrl\":\"http://localhost:1\"}}";
        given().contentType("application/json").body(apiBody.formatted("first"))
            .when().put(service + "/apis/Echo" + API).then().statusCode(lessThan(300));
        given().contentType("application/json").body(apiBody.formatted("second"))
            .when().put(service("SUB-CASE", "rg-children", "apimkids") + "/apis/ECHO" + API)
            .then().statusCode(lessThan(300));

        given().when().get(service + "/apis" + API).then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].properties.displayName", equalTo("second"));
        given().when().get(service("sub-case", "rg-children", "APIMKIDS") + "/apis/echo" + API).then().statusCode(200)
            .body("properties.displayName", equalTo("second"));

        given().when().delete(service + "/apis/eCHO" + API).then().statusCode(lessThan(300));
        given().when().get(service + "/apis" + API).then().statusCode(200).body("value", hasSize(0));
    }

    @Test
    void childrenSurviveARestartAndAResetClearsThem() {
        InMemoryStorage<String, StoredObject> disk = new InMemoryStorage<>();
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create("apim-children")).thenReturn(disk);

        ApiManagementStore before = new ApiManagementStore(factory);
        before.apis().put("sub/rg/apim/s/apis/a", new LinkedHashMap<>(Map.of("name", "a", "_service", "s")));
        before.productApis().put("sub/rg/apim/s/products/p/apis/a", "sub/rg/apim/s/apis/a");

        ApiManagementStore after = new ApiManagementStore(factory);
        assertEquals("a", after.apis().get("sub/rg/apim/s/apis/a").get("name"));
        assertEquals("sub/rg/apim/s/apis/a", after.productApis().get("sub/rg/apim/s/products/p/apis/a"));
        assertTrue(after.operations().isEmpty(), "each kind holds only its own keys");

        after.apis().keySet().removeIf(key -> key.startsWith("sub/rg/apim/s/"));
        assertTrue(after.apis().isEmpty());
        assertEquals(1, after.productApis().size());
        after.productApis().clear();
        assertTrue(disk.keys().isEmpty());
    }
}
