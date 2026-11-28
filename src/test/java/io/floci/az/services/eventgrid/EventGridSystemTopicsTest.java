package io.floci.az.services.eventgrid;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@DisplayName("Event Grid: system topics")
class EventGridSystemTopicsTest {

    private static final String API = "?api-version=2025-02-15";
    private static final String GROUP =
        "/subscriptions/sub-st/resourceGroups/rg-st/providers/Microsoft.EventGrid/systemTopics";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String SOURCE =
        "/subscriptions/sub-st/resourceGroups/rg-st/providers/Microsoft.Storage/storageAccounts/stsource";
    private static final String STORAGE_TYPE = "Microsoft.Storage.StorageAccounts";

    /** The body azurerm's {@code azurerm_eventgrid_system_topic} sends for a storage account's events. */
    private static final String AZURERM_BODY = "{\"location\":\"eastus\",\"tags\":{\"env\":\"test\"},"
        + "\"identity\":{\"type\":\"SystemAssigned\"},"
        + "\"properties\":{\"source\":\"" + SOURCE + "\",\"topicType\":\"" + STORAGE_TYPE + "\"}}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String put(String name, String body, int status) {
        return given().contentType(ContentType.JSON).body(body)
            .when().put(GROUP + "/" + name + API)
            .then().statusCode(status)
            .extract().asString();
    }

    @Test
    void aSystemTopicIsNotFoundUntilAPutCreatesIt() {
        given().when().get(GROUP + "/st" + API)
            .then().statusCode(404);

        put("st", AZURERM_BODY, 201);

        given().when().get(GROUP + "/st" + API)
            .then().statusCode(200)
            .body("id", equalTo(GROUP + "/st"))
            .body("name", equalTo("st"))
            .body("type", equalTo("Microsoft.EventGrid/systemTopics"))
            .body("location", equalTo("eastus"))
            .body("tags.env", equalTo("test"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.source", equalTo(SOURCE))
            .body("properties.topicType", equalTo(STORAGE_TYPE))
            .body("properties.metricResourceId", matchesPattern(UUID_PATTERN))
            .body("identity.type", equalTo("SystemAssigned"))
            .body("identity.principalId", matchesPattern(UUID_PATTERN));
    }

    @Test
    void aRePutAnswers200AndKeepsThePrincipalAndMetricResourceId() {
        put("st", AZURERM_BODY, 201);
        String principal = given().when().get(GROUP + "/st" + API).then().extract().path("identity.principalId");
        String metric = given().when().get(GROUP + "/st" + API).then().extract().path("properties.metricResourceId");

        put("st", AZURERM_BODY.replace("\"env\":\"test\"", "\"env\":\"prod\""), 200);

        given().when().get(GROUP + "/st" + API)
            .then().statusCode(200)
            .body("tags.env", equalTo("prod"))
            .body("identity.principalId", equalTo(principal))
            .body("properties.metricResourceId", equalTo(metric));
    }

    @Test
    void aRePutWithoutAnIdentityRemovesIt() {
        put("st", AZURERM_BODY, 201);
        put("st", AZURERM_BODY.replace("\"identity\":{\"type\":\"SystemAssigned\"},", ""), 200);

        given().when().get(GROUP + "/st" + API)
            .then().statusCode(200)
            .body("identity", nullValue());
    }

    @Test
    void sourceAndTopicTypeAreRequiredOnCreateAndCannotChange() {
        given().contentType(ContentType.JSON)
            .body("{\"location\":\"eastus\",\"properties\":{\"topicType\":\"" + STORAGE_TYPE + "\"}}")
            .when().put(GROUP + "/nosource" + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidRequest"));
        given().contentType(ContentType.JSON)
            .body("{\"location\":\"eastus\",\"properties\":{\"source\":\"" + SOURCE + "\"}}")
            .when().put(GROUP + "/notype" + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidRequest"));

        put("st", AZURERM_BODY, 201);
        put("st", AZURERM_BODY.replace("/stsource", "/othersource"), 400);
        put("st", AZURERM_BODY.replace(STORAGE_TYPE, "Microsoft.KeyVault.vaults"), 400);
        // The source is an ARM id, compared in any casing.
        put("st", AZURERM_BODY.replace("/stsource", "/STSOURCE"), 200);

        given().when().get(GROUP + "/st" + API)
            .then().statusCode(200).body("properties.source", equalTo(SOURCE));
    }

    @Test
    void aPatchReplacesTheTagsAndTheIdentity() {
        put("st", AZURERM_BODY, 201);
        String principal = given().when().get(GROUP + "/st" + API).then().extract().path("identity.principalId");

        given().contentType(ContentType.JSON).body("{\"tags\":{\"team\":\"data\"}}")
            .when().patch(GROUP + "/st" + API)
            .then().statusCode(200)
            .body("tags.team", equalTo("data"))
            .body("tags.env", nullValue())
            .body("identity.principalId", equalTo(principal))
            .body("properties.source", equalTo(SOURCE));

        given().contentType(ContentType.JSON).body("{\"identity\":{\"type\":\"None\"}}")
            .when().patch(GROUP + "/st" + API)
            .then().statusCode(200)
            .body("identity", nullValue());

        given().contentType(ContentType.JSON).body("{\"tags\":{}}")
            .when().patch(GROUP + "/missing" + API)
            .then().statusCode(404);
    }

    @Test
    void systemTopicsAreListedByResourceGroupAndSubscription() {
        put("one", AZURERM_BODY, 201);
        put("two", AZURERM_BODY, 201);

        given().when().get(GROUP + API).then().statusCode(200).body("value", hasSize(2));
        given().when().get("/subscriptions/sub-st/providers/Microsoft.EventGrid/systemTopics" + API)
            .then().statusCode(200).body("value", hasSize(2));
        given().contentType(ContentType.JSON).body("{\"location\":\"eastus\"}")
            .when().put(GROUP + API).then().statusCode(405);
    }

    @Test
    void aSystemTopicIsReadThroughAnotherCasingOfItsId() {
        put("St-Casing", AZURERM_BODY, 201);

        given().when()
            .get("/subscriptions/sub-st/resourcegroups/RG-ST/providers/microsoft.eventgrid/SYSTEMTOPICS/st-casing" + API)
            .then().statusCode(200)
            .body("name", equalTo("St-Casing"));
    }

    @Test
    void deletingASystemTopicRemovesIt() {
        put("st", AZURERM_BODY, 201);

        given().when().delete(GROUP + "/st" + API).then().statusCode(200);
        given().when().get(GROUP + "/st" + API).then().statusCode(404);
        given().when().delete(GROUP + "/st" + API).then().statusCode(204);
    }

    @Test
    void eventSubscriptionsOnASystemTopicAreNotServedYet() {
        put("st", AZURERM_BODY, 201);

        given().contentType(ContentType.JSON).body("{\"properties\":{}}")
            .when().put(GROUP + "/st/eventSubscriptions/sub" + API)
            .then().statusCode(404);
    }
}
