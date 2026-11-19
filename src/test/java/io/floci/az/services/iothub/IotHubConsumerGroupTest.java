package io.floci.az.services.iothub;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The consumer groups of a hub's built-in Event Hub-compatible endpoint. The shapes are the ones Azure answered the az
 * CLI's {@code iot hub consumer-group} commands with (2023-06-30-preview recording).
 */
@QuarkusTest
@DisplayName("Microsoft.Devices: consumer groups of an IoT hub's events endpoint")
class IotHubConsumerGroupTest {

    private static final String SUB = "10700000-0000-4000-8000-000000000002";
    private static final String API = "?api-version=2023-06-30";
    private static final String HUB_BODY = "{\"location\": \"westus2\", \"sku\": {\"name\": \"S1\", \"capacity\": 1}}";
    private static final String RFC_1123 = "[A-Z][a-z]{2}, \\d{1,2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String hub(String name) {
        return "/subscriptions/" + SUB + "/resourceGroups/rg/providers/Microsoft.Devices/IotHubs/" + name;
    }

    private static String groups(String hub) {
        return hub(hub) + "/eventHubEndpoints/events/ConsumerGroups";
    }

    private static void createHub(String name) {
        given().contentType("application/json").body(HUB_BODY).when().put(hub(name) + API).then().statusCode(201);
    }

    /**
     * The PUT the az CLI and azurerm send, {@code {"properties": {"name": ...}}}, with the group's path segment sent
     * exactly as given: a {@code %} escape in it is the client's own.
     */
    private static Response putGroup(String hub, String group) {
        return putGroup(hub, group, "{\"properties\": {\"name\": \""
                + URLDecoder.decode(group, StandardCharsets.UTF_8) + "\"}}");
    }

    private static Response putGroup(String hub, String group, String body) {
        return given().urlEncodingEnabled(false).contentType("application/json").body(body)
                .when().put(groups(hub) + "/" + group + API);
    }

    @Test
    void aNewHubHasTheDefaultGroupCreatedWithIt() {
        createHub("hub-default");

        given().when().get(groups("hub-default") + API).then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].name", equalTo("$Default"))
            .body("value[0].id", equalTo(groups("hub-default") + "/%24Default"))
            .body("value[0].type", equalTo("Microsoft.Devices/IotHubs/EventHubEndpoints/ConsumerGroups"))
            .body("value[0].properties.created", matchesPattern(RFC_1123))
            .body("value[0]", hasKey("etag"))
            .body("value[0].etag", nullValue());
        given().urlEncodingEnabled(false).when().get(groups("hub-default") + "/%24Default" + API).then().statusCode(200)
            .body("name", equalTo("$Default"));
    }

    @Test
    void createShowListAndDeleteAnswerAsAzureAnsweredTheCli() {
        createHub("hub-crud");

        putGroup("hub-crud", "cg1").then().statusCode(200)
            .header("Azure-AsyncOperation", nullValue())
            .body("name", equalTo("cg1"))
            .body("id", equalTo(groups("hub-crud") + "/cg1"))
            .body("type", equalTo("Microsoft.Devices/IotHubs/EventHubEndpoints/ConsumerGroups"))
            .body("etag", nullValue())
            .body("properties.created", matchesPattern(RFC_1123))
            .body("properties.properties.name", equalTo("cg1"));

        // Only the PUT echoes the name it sent.
        given().when().get(groups("hub-crud") + "/cg1" + API).then().statusCode(200)
            .body("name", equalTo("cg1"))
            .body("properties", not(hasKey("properties")));
        given().when().get(groups("hub-crud") + API).then().statusCode(200)
            .body("value.name", contains("$Default", "cg1"));

        given().when().delete(groups("hub-crud") + "/cg1" + API).then().statusCode(200);
        given().when().get(groups("hub-crud") + "/cg1" + API).then().statusCode(404);
        given().when().get(groups("hub-crud") + API).then().body("value.name", contains("$Default"));
    }

    @Test
    void aRepeatedPutKeepsTheGroupAndItsCreationTime() {
        createHub("hub-idempotent");
        String created = putGroup("hub-idempotent", "cg1").then().statusCode(200).extract().path("properties.created");

        putGroup("hub-idempotent", "cg1").then().statusCode(200).body("properties.created", equalTo(created));
        given().when().get(groups("hub-idempotent") + API).then().body("value", hasSize(2));
    }

    @Test
    void groupNamesMatchIgnoringCaseAndAPutEchoesTheNameItSent() {
        createHub("hub-case-names");
        String created = putGroup("hub-case-names", "floci-cg").then().statusCode(200)
            .extract().path("properties.created");

        putGroup("hub-case-names", "Floci-CG").then().statusCode(200)
            .body("name", equalTo("floci-cg"))
            .body("properties.properties.name", equalTo("Floci-CG"))
            .body("properties.created", equalTo(created));
        putGroup("hub-case-names", "$DEFAULT").then().statusCode(200).body("name", equalTo("$Default"));
        given().when().get(groups("hub-case-names") + "/FLOCI-CG" + API).then().statusCode(200);
        given().when().get(groups("hub-case-names") + API).then().body("value.name", contains("$Default", "floci-cg"));
        given().urlEncodingEnabled(false).when().delete(groups("hub-case-names") + "/%24default" + API).then().statusCode(400);
        // The echo is the body's name, whatever the path's spelling.
        putGroup("hub-case-names", "floci-cg", "{\"properties\": {\"name\": \"FLOCI-cg\"}}").then().statusCode(200)
            .body("name", equalTo("floci-cg"))
            .body("properties.properties.name", equalTo("FLOCI-cg"));
    }

    @Test
    void aPutWithoutTheBodyTheSpecRequiresCreatesNothing() {
        createHub("hub-bodies");

        given().urlEncodingEnabled(false).when().put(groups("hub-bodies") + "/cg1" + API).then().statusCode(400)
            .body("error.code", equalTo("InvalidRequestContent"));
        for (String body : new String[] {"{}", "{\"properties\": []}", "{\"properties\": {}}",
                "{\"properties\": {\"name\": null}}", "{\"properties\": {\"name\": 123}}"}) {
            putGroup("hub-bodies", "cg1", body).then().statusCode(400)
                .body("error.code", equalTo("InvalidRequestContent"));
        }
        putGroup("hub-bodies", "123", "{\"properties\": {\"name\": 123}}").then().statusCode(400);

        given().when().get(groups("hub-bodies") + API).then().body("value.name", contains("$Default"));
    }

    @Test
    void azurermsLowerCasePathServesTheSameGroups() {
        createHub("hub-casing");
        String lower = "/subscriptions/" + SUB
                + "/resourceGroups/rg/providers/Microsoft.Devices/iotHubs/hub-casing/eventHubEndpoints/events"
                + "/consumerGroups/floci-cg";

        given().contentType("application/json").body("{\"properties\": {\"name\": \"floci-cg\"}}")
            .when().put(lower + API).then().statusCode(200)
            .body("id", equalTo(groups("hub-casing") + "/floci-cg"));
        given().when().get(lower + API).then().statusCode(200);
        given().when().delete(lower + API).then().statusCode(200);
    }

    @Test
    void badRequestsAreRefusedAndChangeNothing() {
        createHub("hub-refusals");

        given().when().get(hub("hub-refusals") + "/eventHubEndpoints/other/ConsumerGroups" + API)
            .then().statusCode(404);
        given().contentType("application/json").body("{\"properties\": {\"name\": \"cg\"}}")
            .when().put(hub("hub-refusals") + "/eventHubEndpoints/other/ConsumerGroups/cg" + API)
            .then().statusCode(404);
        // The name rule holds in every mode, as the gate answers it.
        putGroup("hub-refusals", "bad%20name").then().statusCode(400)
            .body("error.code", equalTo("InvalidResourceName"));
        putGroup("hub-refusals", "x".repeat(51)).then().statusCode(400)
            .body("error.code", equalTo("InvalidResourceName"));
        // The path arrives decoded once: a % in it is a name character to refuse or not find, never a 500.
        putGroup("hub-refusals", "100%25").then().statusCode(400);
        given().when().get(groups("hub-refusals") + "/100%25" + API).then().statusCode(404);
        given().urlEncodingEnabled(false).when().get(groups("hub-refusals") + "/%2524Default" + API).then().statusCode(404);
        given().contentType("application/json").body("{\"properties\": {\"name\": \"other\"}}")
            .when().put(groups("hub-refusals") + "/cg1" + API).then().statusCode(400)
            .body("error.message", containsString("not the one in the path"));

        given().urlEncodingEnabled(false).when().delete(groups("hub-refusals") + "/%24Default" + API).then().statusCode(400);
        given().when().delete(groups("hub-refusals") + "/missing" + API).then().statusCode(404);
        given().when().get(groups("missing-hub") + API).then().statusCode(404);
        putGroup("missing-hub", "cg1").then().statusCode(404);
        // A missing hub answers before the body is looked at, as GET, list and DELETE answer it.
        given().contentType("application/json").body("{\"properties\": {\"name\": \"other\"}}")
            .when().put(groups("missing-hub") + "/cg1" + API).then().statusCode(404);

        given().when().get(groups("hub-refusals") + API).then().body("value.name", contains("$Default"));
    }

    @Test
    void aHubHoldsAtMostTwentyGroupsDefaultIncluded() {
        createHub("hub-limit");
        for (int i = 1; i < 20; i++) {
            putGroup("hub-limit", "cg" + i).then().statusCode(200);
        }

        putGroup("hub-limit", "cg20").then().statusCode(400).body("error.message", containsString("20"));
        // A repeated PUT of a group it has is not a new group.
        putGroup("hub-limit", "cg1").then().statusCode(200);
        given().when().delete(groups("hub-limit") + "/cg1" + API).then().statusCode(200);
        putGroup("hub-limit", "cg20").then().statusCode(200);
    }

    @Test
    void groupsSurviveHubUpdatesWithoutChangingItsETagAndGoWithTheHub() {
        createHub("hub-keeps");
        putGroup("hub-keeps", "cg1").then().statusCode(200);
        String etag = given().when().get(hub("hub-keeps") + API).then().extract().path("etag");

        // Group writes leave the hub's ETag alone, so a client's If-Match from before them still holds.
        putGroup("hub-keeps", "cg2").then().statusCode(200);
        given().when().delete(groups("hub-keeps") + "/cg2" + API).then().statusCode(200);
        assertEquals(etag, given().when().get(hub("hub-keeps") + API).then().extract().path("etag"));

        // The body azurerm_iothub sends, then the CLI's read-modify-write under If-Match: both keep the groups.
        given().contentType("application/json").body("""
                {"location": "westus2", "sku": {"name": "S1", "capacity": 1}, "tags": {"env": "b"},
                 "properties": {"cloudToDevice": {}, "messagingEndpoints": {}, "storageEndpoints": {},
                                "eventHubEndpoints": {"events": {"partitionCount": 4, "retentionTimeInDays": 1}}}}""")
            .when().put(hub("hub-keeps") + API).then().statusCode(201);
        String shown = given().when().get(hub("hub-keeps") + API).then().extract().asString();
        String current = given().when().get(hub("hub-keeps") + API).then().extract().path("etag");
        given().contentType("application/json").header("If-Match", current).body(shown)
            .when().put(hub("hub-keeps") + API).then().statusCode(201);
        given().when().get(groups("hub-keeps") + API).then().body("value.name", contains("$Default", "cg1"));

        // A hub created again in its place starts over with $Default only.
        given().when().delete(hub("hub-keeps") + API).then().statusCode(200);
        given().when().get(groups("hub-keeps") + API).then().statusCode(404);
        createHub("hub-keeps");
        given().when().get(groups("hub-keeps") + API).then().body("value.name", contains("$Default"));
    }
}
