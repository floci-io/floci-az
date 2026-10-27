package io.floci.az.core.ui;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * The status endpoint before anything opened {@code /_floci/ui}: the console is enabled by default but nothing
 * has started it, so it is neither ready nor failed. This test never opens the page, which would start a
 * container.
 */
@QuarkusTest
class UiStatusTest {

    @Test
    void openingTheRootInABrowserServesTheDashboardWithoutStartingTheConsole() {
        given()
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .when().get("/")
            .then()
            .statusCode(200)
            .contentType(containsString("text/html"))
            .body(containsString("id=\"h-connect\""));

        given()
            .when().get("/_floci/ui/status")
            .then()
            .statusCode(200)
            .body("url", nullValue())
            .body("error", nullValue());
    }

    @Test
    void beforeTheConsoleIsOpenedItIsNotReadyAndNotFailed() {
        given()
            .when().get("/_floci/ui/status")
            .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("ready", is(false))
            .body("url", nullValue())
            .body("error", nullValue());
    }
}
