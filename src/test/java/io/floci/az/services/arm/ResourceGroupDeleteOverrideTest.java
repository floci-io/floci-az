package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * {@code parity-overrides.resource-group-delete} alone: {@code DELETE} is the long-running cascade and the
 * generic listings show what it deletes, while every PUT keeps its default answer.
 */
@QuarkusTest
@TestProfile(ResourceGroupDeleteOverrideTest.DeleteOnly.class)
@DisplayName("ARM resource groups: resource-group-delete override alone")
class ResourceGroupDeleteOverrideTest {

    public static class DeleteOnly implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.resource-group-delete", "true");
        }
    }

    private static final String SUB = "7a1d0000-0000-4000-8000-00000000c0a4";
    private static final String API = "?api-version=2024-11-01";
    private static final String EVENT_GRID_API = "?api-version=2022-06-15";
    private static final String MONITOR_API = "?api-version=2022-10-01";
    private static final String EMAIL_API = "?api-version=2023-04-01";

    private static String group(String name) {
        return "/subscriptions/" + SUB + "/resourceGroups/" + name;
    }

    private static RequestSpecification json(String body) {
        return given().contentType("application/json").body(body);
    }

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void everyPutStays201() {
        json("{\"location\":\"eastus\"}").when().put(group("rg-del-only") + API).then().statusCode(201);
        json("{\"location\":\"eastus\"}").when().put(group("rg-del-only") + API).then().statusCode(201);
    }

    @Test
    void theListingsShowAndTheCascadeDeletesEventGridMonitorAndEmailResources() {
        json("{\"location\":\"eastus\"}").when().put(group("rg-idx") + API).then().statusCode(201);
        String topic = group("rg-idx") + "/providers/Microsoft.EventGrid/topics/idx-topic";
        String workspace = group("rg-idx") + "/providers/Microsoft.OperationalInsights/workspaces/idx-ws";
        String email = group("rg-idx") + "/providers/Microsoft.Communication/emailServices/idx-email";
        json("{\"location\":\"eastus\",\"properties\":{}}").when().put(topic + EVENT_GRID_API).then().statusCode(200);
        json("{\"location\":\"eastus\",\"properties\":{}}").when().put(workspace + MONITOR_API).then().statusCode(201);
        json("{\"location\":\"global\",\"properties\":{\"dataLocation\":\"United States\"}}")
            .when().put(email + EMAIL_API).then().statusCode(201);

        given().when().get(group("rg-idx") + "/resources" + API)
            .then().statusCode(200)
            .body("value.id", hasItem(topic))
            .body("value.id", hasItem(workspace))
            .body("value.id", hasItem(email))
            .body("value.find { it.id == '" + workspace + "' }.type",
                equalTo("Microsoft.OperationalInsights/workspaces"));

        given().when().delete(group("rg-idx") + API)
            .then().statusCode(202)
            .header("Location", notNullValue());

        given().when().get(topic + EVENT_GRID_API).then().statusCode(404);
        given().when().get(workspace + MONITOR_API).then().statusCode(404);
        given().when().get(email + EMAIL_API).then().statusCode(404);
    }
}
