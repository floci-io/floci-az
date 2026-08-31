package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.startsWith;

/**
 * Tests for {@link AzureIdentityController#metadataEndpoints}: the ARM environment
 * discovery document consumed by go-azure-sdk / terraform's azurerm provider in
 * Azure Stack (custom-environment) mode.
 *
 * <p>Regression coverage for issue #252: behind a TLS-terminating reverse proxy the
 * proxy&#8594;emulator hop is plaintext, so the request arrives over {@code http}, but the
 * self-referential URLs in this document must advertise the scheme the CLIENT used
 * ({@code https}, signalled via {@code X-Forwarded-Proto}). Without it the SDK takes
 * {@code loginEndpoint} at face value and sends a plaintext token request to the
 * TLS-only listener, and the apply never reaches a resource.
 */
@QuarkusTest
@DisplayName("AzureIdentityController — /metadata/endpoints scheme handling")
class AzureIdentityControllerTest {

    @Test
    @DisplayName("without X-Forwarded-Proto, URLs use the request's own (http) scheme")
    void defaultsToRequestScheme() {
        given()
                .when().get("/metadata/endpoints")
                .then().statusCode(200)
                .body("resourceManager", startsWith("http://"))
                .body("authentication.loginEndpoint", startsWith("http://"));
    }

    @Test
    @DisplayName("X-Forwarded-Proto: https makes every self-referential URL https")
    void honorsForwardedProtoHttps() {
        given()
                .header("X-Forwarded-Proto", "https")
                .when().get("/metadata/endpoints")
                .then().statusCode(200)
                .body("resourceManager", startsWith("https://"))
                .body("microsoftGraphResourceId", startsWith("https://"))
                .body("portal", startsWith("https://"))
                .body("gallery", startsWith("https://"))
                .body("authentication.loginEndpoint", startsWith("https://"))
                .body("authentication.audiences[0]", startsWith("https://"));
    }

    @Test
    @DisplayName("a proxy chain's first X-Forwarded-Proto value wins")
    void honorsFirstValueInAForwardedProtoChain() {
        given()
                .header("X-Forwarded-Proto", "https, http")
                .when().get("/metadata/endpoints")
                .then().statusCode(200)
                .body("resourceManager", startsWith("https://"));
    }

    @Test
    @DisplayName("a garbage X-Forwarded-Proto is ignored, not spliced into the URL")
    void ignoresAnUnrecognizedForwardedProto() {
        given()
                .header("X-Forwarded-Proto", "gopher")
                .when().get("/metadata/endpoints")
                .then().statusCode(200)
                .body("resourceManager", startsWith("http://"));
    }
}
