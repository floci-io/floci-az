package io.floci.az.services.entra;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Instance discovery as MSAL sends it before a token request:
 * {@code GET https://{host}/common/discovery/instance?api-version=1.1&authorization_endpoint={authorize url}}
 * (MSAL Go, MSAL4J, msal-js; MSAL Python sends {@code api-version=1.0}).
 */
@QuarkusTest
class InstanceDiscoveryEndpointTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String LOGIN_HOST = "login.microsoftonline.com";

    @Test
    void discoveryNamesTheTenantsV2ConfigurationOnTheRequestHost() {
        given()
            .header("Host", LOGIN_HOST)
            .queryParam("api-version", "1.1")
            .queryParam("authorization_endpoint", "https://" + LOGIN_HOST + "/" + TENANT + "/oauth2/v2.0/authorize")
          .when().get("/common/discovery/instance")
          .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("tenant_discovery_endpoint",
                endsWith("://" + LOGIN_HOST + "/" + TENANT + "/v2.0/.well-known/openid-configuration"))
            .body("api-version", is("1.1"))
            .body("metadata", hasSize(1))
            .body("metadata[0].preferred_network", is(LOGIN_HOST))
            .body("metadata[0].preferred_cache", is(LOGIN_HOST))
            .body("metadata[0].aliases", contains(LOGIN_HOST));
    }

    @Test
    void theTenantDiscoveryEndpointIsServed() {
        String endpoint = given()
            .queryParam("api-version", "1.1")
            .queryParam("authorization_endpoint", "https://" + LOGIN_HOST + "/" + TENANT + "/oauth2/v2.0/authorize")
          .when().get("/common/discovery/instance")
          .then().statusCode(200)
            .extract().path("tenant_discovery_endpoint");

        given()
          .when().get(URI.create(endpoint).getPath())
          .then()
            .statusCode(200)
            .body("token_endpoint", containsString("/" + TENANT + "/oauth2/v2.0/token"));
    }

    @Test
    void aCommonAuthorizeUrlNamesTheCommonConfiguration() {
        given()
            .queryParam("api-version", "1.1")
            .queryParam("authorization_endpoint", "https://" + LOGIN_HOST + "/common/oauth2/v2.0/authorize")
          .when().get("/common/discovery/instance")
          .then()
            .statusCode(200)
            .body("tenant_discovery_endpoint", endsWith("/common/v2.0/.well-known/openid-configuration"));
    }

    @Test
    void aV1AuthorizeUrlNamesTheV1Configuration() {
        given()
            .queryParam("api-version", "1.0")
            .queryParam("authorization_endpoint", "https://" + LOGIN_HOST + "/" + TENANT + "/oauth2/authorize")
          .when().get("/common/discovery/instance")
          .then()
            .statusCode(200)
            .body("tenant_discovery_endpoint", endsWith("/" + TENANT + "/.well-known/openid-configuration"));
    }

    @Test
    void withNoAuthorizeUrlTheCommonConfigurationIsNamed() {
        given()
            .queryParam("api-version", "1.1")
          .when().get("/common/discovery/instance")
          .then()
            .statusCode(200)
            .body("tenant_discovery_endpoint", endsWith("/common/v2.0/.well-known/openid-configuration"));
    }

    @Test
    void aChinaLoginHostListsTheOtherChinaLoginHostAsAnAlias() {
        given()
            .header("Host", "login.chinacloudapi.cn")
            .queryParam("api-version", "1.1")
          .when().get("/common/discovery/instance")
          .then()
            .statusCode(200)
            .body("metadata[0].preferred_network", is("login.chinacloudapi.cn"))
            .body("metadata[0].aliases", contains("login.chinacloudapi.cn", "login.partner.microsoftonline.cn"));
    }

    @Test
    void aPortTheRequestCarriedIsKeptInTheMetadata() {
        given()
            .header("Host", "localhost:4577")
            .queryParam("api-version", "1.1")
            .queryParam("authorization_endpoint", "http://localhost:4577/" + TENANT + "/oauth2/v2.0/authorize")
          .when().get("/common/discovery/instance")
          .then()
            .statusCode(200)
            .body("tenant_discovery_endpoint",
                is("http://localhost:4577/" + TENANT + "/v2.0/.well-known/openid-configuration"))
            .body("metadata[0].preferred_network", is("localhost:4577"))
            .body("metadata[0].preferred_cache", is("localhost:4577"))
            .body("metadata[0].aliases", contains("localhost:4577"));
    }

    @Test
    void instanceDiscoveryIsNotServedUnderASpecificTenant() {
        given()
            .queryParam("api-version", "1.1")
          .when().get("/" + TENANT + "/discovery/instance")
          .then()
            .statusCode(not(200));
    }
}
