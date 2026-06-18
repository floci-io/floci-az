package io.floci.az.services.entra;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class EntraServiceTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";

    @Test
    void discoveryDocumentExposesEndpoints() {
        given()
          .when().get("/{tenant}/v2.0/.well-known/openid-configuration", TENANT)
          .then()
            .statusCode(200)
            .body("issuer", endsWith("/" + TENANT + "/v2.0"))
            .body("token_endpoint", endsWith("/" + TENANT + "/oauth2/v2.0/token"))
            .body("jwks_uri", endsWith("/" + TENANT + "/discovery/v2.0/keys"))
            .body("id_token_signing_alg_values_supported", hasItem("RS256"))
            .body("grant_types_supported", hasItems("client_credentials", "password"));
    }

    @Test
    void jwksExposesRsaSigningKey() {
        given()
          .when().get("/{tenant}/discovery/v2.0/keys", TENANT)
          .then()
            .statusCode(200)
            .body("keys[0].kty", is("RSA"))
            .body("keys[0].use", is("sig"))
            .body("keys[0].alg", is("RS256"))
            .body("keys[0].kid", not(emptyOrNullString()))
            .body("keys[0].n", not(emptyOrNullString()))
            .body("keys[0].e", not(emptyOrNullString()))
            // self-signed cert chain + thumbprint, as real Entra JWKS entries carry
            .body("keys[0].x5c[0]", not(emptyOrNullString()))
            .body("keys[0].x5t", not(emptyOrNullString()));
    }

    @Test
    void clientCredentialsGrantReturnsSignedToken() {
        given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "client_credentials")
          .formParam("client_id", EntraStore.DEV_CLIENT_ID)
          .formParam("client_secret", EntraStore.DEV_CLIENT_SECRET)
          .formParam("scope", "api://resource/.default")
          .when().post("/{tenant}/oauth2/v2.0/token", TENANT)
          .then()
            .statusCode(200)
            .body("token_type", is("Bearer"))
            .body("expires_in", is(3599))
            .body("ext_expires_in", is(3599))
            // signed JWT: three base64url segments
            .body("access_token", matchesPattern("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"));
    }

    @Test
    void passwordGrantReturnsScopedToken() {
        given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "password")
          .formParam("client_id", EntraStore.DEV_CLIENT_ID)
          .formParam("username", "dev-user@floci-az.local")
          .formParam("password", "whatever")
          .formParam("scope", "openid api://resource/user_impersonation")
          .when().post("/{tenant}/oauth2/v2.0/token", TENANT)
          .then()
            .statusCode(200)
            .body("token_type", is("Bearer"))
            .body("scope", is("api://resource/user_impersonation"))
            .body("access_token", not(emptyOrNullString()));
    }

    @Test
    void unsupportedGrantReturnsOauthError() {
        given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "authorization_code")
          .formParam("code", "abc")
          .when().post("/{tenant}/oauth2/v2.0/token", TENANT)
          .then()
            .statusCode(400)
            .body("error", is("unsupported_grant_type"))
            // Azure-shaped error: discrete codes + diagnostics MSAL/azure-identity parse
            .body("error_codes", hasItem(70003))
            .body("trace_id", not(emptyOrNullString()))
            .body("correlation_id", not(emptyOrNullString()))
            .body("error_description", containsString("AADSTS70003"));
    }

    @Test
    void commonTenantAliasResolvesToDefaultTenant() {
        given()
          .when().get("/common/v2.0/.well-known/openid-configuration")
          .then()
            .statusCode(200)
            .body("issuer", endsWith("/common/v2.0"));
    }
}
