package io.floci.az.services.monitor;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@DisplayName("Monitor: workspaces and data collection endpoints and rules keep their tags")
class MonitorTagsTest {

    private static final String API = "?api-version=2022-10-01";
    private static final String GROUP = "/subscriptions/Sub-Tags/resourceGroups/RG-Tags/providers/";
    private static final String TAGGED =
        "{\"location\":\"eastus\",\"tags\":{\"env\":\"test\",\"owner\":\"azine\"},\"properties\":{}}";
    private static final String UNTAGGED = "{\"location\":\"eastus\",\"properties\":{}}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Microsoft.OperationalInsights/workspaces", "Microsoft.Insights/dataCollectionEndpoints",
        "Microsoft.Insights/dataCollectionRules"})
    void tagsSentOnPutComeBackOnPutGetAndList(String type) {
        String resource = GROUP + type + "/tagged";

        given().contentType(ContentType.JSON).body(TAGGED)
            .when().put(resource + API).then().statusCode(201)
            .body("tags.env", equalTo("test"))
            .body("tags.owner", equalTo("azine"));

        given().when().get(resource + API).then().statusCode(200)
            .body("tags.env", equalTo("test"))
            .body("tags.owner", equalTo("azine"));
        given().when().get(GROUP + type + API).then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].tags.env", equalTo("test"))
            .body("value[0].tags.owner", equalTo("azine"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Microsoft.OperationalInsights/workspaces", "Microsoft.Insights/dataCollectionEndpoints",
        "Microsoft.Insights/dataCollectionRules"})
    void aPutWithoutTagsClearsThem(String type) {
        String resource = GROUP + type + "/retagged";
        given().contentType(ContentType.JSON).body(TAGGED).when().put(resource + API).then().statusCode(201);

        given().contentType(ContentType.JSON).body(UNTAGGED)
            .when().put(resource + API).then().statusCode(201)
            .body("tags", nullValue());

        given().when().get(resource + API).then().statusCode(200).body("tags", nullValue());
    }
}
