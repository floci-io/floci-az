package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The floci-loft 0022 control-plane contract: the same scenarios every Floci emulator runs. Canonical
 * endpoints answer with their required fields, every legacy alias answers as before, an unknown
 * {@code /_floci/*} path gets the JSON 404, an account-style Host does not capture the control plane,
 * and a reset clears what was created before it.
 */
@QuarkusTest
class ControlPlaneContractTest {

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/health", "/health"})
    void healthCarriesTheRequiredFields(String path) {
        given()
            .when().get(path)
            .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("status", is("UP"))
            .body("cloud", is("az"))
            .body("version", notNullValue())
            .body("services.blob", is("running"))
            .body("services.queue", is("running"))
            .body("edition", is("floci-az-always-free"));
    }

    @Test
    void infoCarriesTheRequiredFields() {
        given()
            .when().get("/_floci/info")
            .then()
            .statusCode(200)
            .body("version", notNullValue())
            .body("cloud", is("az"));
    }

    @Test
    void initReportsTheSharedShapeWithNoScripts() {
        given()
            .when().get("/_floci/init")
            .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("completed.boot", is(true))
            .body("completed.start", is(true))
            .body("completed.ready", is(true))
            .body("completed.shutdown", is(false))
            .body("scripts.boot", empty())
            .body("scripts.start", empty())
            .body("scripts.ready", empty())
            .body("scripts.shutdown", empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/state/reset", "/_floci/state/nuke"})
    void stateResetClearsAResourceCreatedBeforeIt(String path) {
        given().contentType("application/json")
            .body("{\"value\": \"reset-me\"}")
            .when().put("/devstoreaccount1-appconfig/kv/contract-reset-key?api-version=1.0")
            .then().statusCode(200);

        given()
            .when().post(path)
            .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("status", is("OK"));

        given()
            .when().get("/devstoreaccount1-appconfig/kv/contract-reset-key?api-version=1.0")
            .then().statusCode(404);
    }

    @Test
    void tlsCertExplainsWhyThereIsNoCertificateWhenTlsIsOff() {
        given()
            .when().get("/_floci/tls-cert")
            .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("tlsEnabled", is(false))
            .body("error", notNullValue())
            .body("message", notNullValue());
    }

    @Test
    void legacyAliasesAnswerAsBefore() {
        given().when().get("/ready")
            .then().statusCode(200).body("status", is("UP"));
        given().when().post("/_admin/reset")
            .then().statusCode(204);
    }

    @Test
    void anUnknownControlPlanePathIsAJsonNotFound() {
        given()
            .when().get("/_floci/does-not-exist")
            .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("error", is("NotFound"))
            .body("path", is("/_floci/does-not-exist"));

        given()
            .when().post("/_floci/state/does-not-exist")
            .then()
            .statusCode(404)
            .body("error", is("NotFound"))
            .body("path", is("/_floci/state/does-not-exist"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"devstoreaccount1.blob.localhost", "devstoreaccount1.blob.core.windows.net"})
    void anAccountHostDoesNotCaptureTheControlPlane(String host) {
        given()
            .header("Host", host)
            .when().get("/_floci/health")
            .then()
            .statusCode(200)
            .body("status", is("UP"))
            .body("cloud", is("az"));

        given()
            .header("Host", host)
            .when().get("/_floci/does-not-exist")
            .then()
            .statusCode(404)
            .body("error", is("NotFound"));
    }

    @Test
    void anUnknownPathUnderAMoreSpecificResourceIsAJsonNotFound() {
        given()
            .when().get("/_floci/ui/does-not-exist")
            .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("error", is("NotFound"))
            .body("path", is("/_floci/ui/does-not-exist"));
    }

    @Test
    void optionsOnAnUnknownPathIsAJsonNotFound() {
        given()
            .when().options("/_floci/does-not-exist")
            .then()
            .statusCode(404)
            .body("error", is("NotFound"))
            .body("path", is("/_floci/does-not-exist"));
    }

    @Test
    void optionsOnAContractEndpointNamesItsMethods() {
        given().when().options("/_floci/health")
            .then().statusCode(200).header("Allow", containsString("GET"));
        given().when().options("/_floci/state/reset")
            .then().statusCode(200).header("Allow", containsString("POST"));
    }

    @Test
    void theOtherControlPlanePathsStillWinOverTheCatchAll() {
        given().when().get("/_floci/ui/status")
            .then().statusCode(200).body("$", hasKey("ready"));
    }
}
