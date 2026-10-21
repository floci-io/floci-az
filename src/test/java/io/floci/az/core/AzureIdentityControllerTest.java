package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
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
@DisplayName("AzureIdentityController — /metadata/endpoints shapes and scheme handling")
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

    @Test
    @DisplayName("2022-09-01 and a request without an api-version answer the emulator's single object")
    void theDefaultShapeIsTheSingleObject() {
        for (String query : new String[] {"", "?api-version=2022-09-01"}) {
            given()
                    .when().get("/metadata/endpoints" + query)
                    .then().statusCode(200)
                    .body("name", equalTo("floci-az"))
                    .body("suffixes.storage", equalTo("core.windows.net"))
                    .body("cloudEndpoint", nullValue());
        }
    }

    @Test
    @DisplayName("2019-05-01 is a one-cloud list named after the default cloud, with the emulator's endpoints")
    void theCloudListHasOneDefaultCloudAtTheEmulator() {
        given()
                .header("X-Forwarded-Proto", "https")
                .when().get("/metadata/endpoints?api-version=2019-05-01")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].name", equalTo("AzureCloud"))
                .body("[0].resourceManager", startsWith("https://"))
                .body("[0].resourceManager", not(equalTo("https://management.azure.com/")))
                .body("[0].authentication.loginEndpoint", startsWith("https://"))
                .body("[0].authentication.audiences", hasSize(1))
                .body("[0].portal", startsWith("https://"))
                .body("[0].suffixes.sqlServerHostname", equalTo("database.windows.net"))
                .body("[0].suffixes.keyVaultDns", equalTo("vault.azure.net"));
    }

    @Test
    @DisplayName("2018-01-01 keeps Azure's cloudEndpoint entries, so azurerm keeps its location fallback")
    void theCloudEndpointDocumentKeepsAzuresEntries() {
        given()
                .when().get("/metadata/endpoints?api-version=2018-01-01")
                .then().statusCode(200)
                .body("cloudEndpoint.public.endpoint", equalTo("management.azure.com"))
                .body("cloudEndpoint.public.locations", hasItem("eastus"))
                .body("cloudEndpoint.usGovCloud.endpoint", equalTo("management.usgovcloudapi.net"))
                .body("authentication.loginEndpoint", startsWith("http://"))
                .body("portalEndpoint", startsWith("http://"))
                .body("galleryEndpoint", startsWith("http://"));
    }
}
