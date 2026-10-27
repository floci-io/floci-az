package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/** {@code GET /_floci/info}, the Floci Console Contract v2 emulator manifest. */
@QuarkusTest
class EmulatorInfoTest {

    @Test
    void theManifestNamesTheEmulatorAndTheContract() {
        given()
            .when().get("/_floci/info")
            .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("manifest_version", is(1))
            .body("console_contract", is(2))
            .body("cloud", is("az"))
            .body("emulator", is("floci-az"))
            .body("edition", is("floci-az-always-free"))
            .body("version", notNullValue())
            .body("endpoint", is("http://localhost:4577"))
            .body("health_url", is("/_floci/health"))
            .body("tls.enabled", is(false));
    }

    @Test
    void theManifestListsTheServicesAndWhetherEachIsEnabled() {
        given()
            .when().get("/_floci/info")
            .then()
            .statusCode(200)
            .body("services.blob", is("enabled"))
            .body("services.queue", is("enabled"))
            .body("services.keyvault", is("enabled"));
    }

    @Test
    void theManifestCarriesTheDefaultsAConsoleWouldOtherwiseGuess() {
        given()
            .when().get("/_floci/info")
            .then()
            .statusCode(200)
            .body("defaults.cloud", is("AzureCloud"))
            .body("defaults.subscription_id", is("00000000-0000-0000-0000-000000000001"))
            .body("defaults.tenant_id", is("00000000-0000-0000-0000-000000000002"))
            .body("defaults.location", notNullValue())
            .body("defaults.storage_account", is("devstoreaccount1"))
            .body("ui.enabled", is(true))
            .body("ui.port", is(4500));
    }

    @Test
    void theManifestIsServedOutsideAzureRouting() {
        // Through Azure routing, "_floci" would be read as a storage account and answered in Storage's XML shape.
        given()
            .when().get("/_floci/info")
            .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("emulator", is("floci-az"));
    }
}
