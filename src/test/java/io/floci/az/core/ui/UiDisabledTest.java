package io.floci.az.core.ui;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/** With {@code floci-az.ui.enabled=false} the page still loads and says why the console will not start. */
@QuarkusTest
@TestProfile(UiDisabledTest.DisabledUiProfile.class)
class UiDisabledTest {

    public static class DisabledUiProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.ui.enabled", "false");
        }
    }

    @Test
    void thePageStillLoads() {
        given()
            .when().get("/_floci/ui")
            .then()
            .statusCode(200)
            .contentType(containsString("text/html"))
            .body(containsString("/_floci/ui/status"));
    }

    @Test
    void aBrowserOpeningTheRootGetsTheDashboard() {
        given()
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .when().get("/")
            .then()
            .statusCode(200)
            .contentType(containsString("text/html"))
            .body(containsString("id=\"h-connect\""));
    }

    @Test
    void aJsonClientAtTheRootStillGetsTheCosmosAccount() {
        given()
            .header("Accept", "application/json")
            .when().get("/")
            .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }

    @Test
    void theStatusSaysTheConsoleIsDisabledAndHowToEnableIt() {
        given()
            .when().get("/_floci/ui/status")
            .then()
            .statusCode(200)
            .body("ready", is(false))
            .body("url", nullValue())
            .body("error", containsString("FLOCI_AZ_UI_ENABLED"));
    }

    @Test
    void theManifestReportsTheConsoleDisabled() {
        given()
            .when().get("/_floci/info")
            .then()
            .statusCode(200)
            .body("ui.enabled", is(false));
    }
}
