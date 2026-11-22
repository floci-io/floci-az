package io.floci.az.services.monitor;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** A GET of a missing Monitor resource answers ARM's {@code 404 ResourceNotFound} envelope, not plain text. */
@QuarkusTest
@DisplayName("Monitor: a missing workspace, endpoint or rule answers the ARM ResourceNotFound envelope")
class MonitorNotFoundTest {

    private static final String API = "?api-version=2022-10-01";
    private static final String GROUP = "/subscriptions/sub-missing/resourceGroups/rg-missing/providers/";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Microsoft.OperationalInsights/workspaces", "Microsoft.Insights/dataCollectionEndpoints",
        "Microsoft.Insights/dataCollectionRules"})
    void aGetOfAMissingResourceAnswersResourceNotFound(String type) {
        given().when().get(GROUP + type + "/absent" + API)
            .then().statusCode(404)
            .contentType(ContentType.JSON)
            .body("error.code", equalTo("ResourceNotFound"))
            .body("error.message", equalTo("The Resource '" + type + "/absent' under resource group 'rg-missing'"
                + " was not found. For more details please go to https://aka.ms/ARMResourceNotFoundFix"));
    }

    @Test
    void sharedKeysOfAMissingWorkspaceAnswerResourceNotFound() {
        given().when().post(GROUP + "Microsoft.OperationalInsights/workspaces/absent/sharedKeys" + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
    }
}
