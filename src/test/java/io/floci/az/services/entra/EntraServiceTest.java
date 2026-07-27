package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
          .formParam("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
          .formParam("device_code", "abc")
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
    void authorizeEndpointRequiresRedirectUri() {
        // Also pins down that the routing filter recognizes the path as Entra (JSON oauth error)
        // rather than misreading {tenant} as a storage account name.
        given()
          .when().get("/{tenant}/oauth2/v2.0/authorize", TENANT)
          .then()
            .statusCode(400)
            .contentType("application/json")
            .body("error", is("invalid_request"));
    }

    @Test
    void authorizeAutoApprovesAndRedirectsWithCode() {
        given()
          .redirects().follow(false)
          .queryParam("client_id", EntraStore.DEV_CLIENT_ID)
          .queryParam("redirect_uri", "https://app.local/callback")
          .queryParam("response_type", "code")
          .queryParam("state", "xyz")
          .when().get("/{tenant}/oauth2/v2.0/authorize", TENANT)
          .then()
            .statusCode(302)
            .header("Location", allOf(startsWith("https://app.local/callback?"), containsString("state=xyz")));
    }

    @Test
    void authorizationCodeGrantExchangesForAccessAndIdTokenWithPkce() throws Exception {
        String verifier = "test-code-verifier-1234567890-abcdefghijklmnop";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));

        Response authorize = given()
          .redirects().follow(false)
          .queryParam("client_id", EntraStore.DEV_CLIENT_ID)
          .queryParam("redirect_uri", "https://app.local/callback")
          .queryParam("response_type", "code")
          .queryParam("nonce", "nonce-123")
          .queryParam("code_challenge", challenge)
          .queryParam("code_challenge_method", "S256")
          .when().get("/{tenant}/oauth2/v2.0/authorize", TENANT);
        String code = queryParam(authorize.header("Location"), "code");

        Response token = given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "authorization_code")
          .formParam("client_id", EntraStore.DEV_CLIENT_ID)
          .formParam("redirect_uri", "https://app.local/callback")
          .formParam("code", code)
          .formParam("code_verifier", verifier)
          .when().post("/{tenant}/oauth2/v2.0/token", TENANT);

        token.then()
            .statusCode(200)
            .body("access_token", not(emptyOrNullString()))
            .body("id_token", not(emptyOrNullString()));

        Map<?, ?> idClaims = decodeJwtClaims(token.jsonPath().getString("id_token"));
        assertEquals("nonce-123", idClaims.get("nonce"));
        assertEquals(EntraStore.DEV_CLIENT_ID, idClaims.get("aud"), "id_token audience must be the client id");
        assertEquals(EntraStore.DEV_USER_UPN, idClaims.get("preferred_username"));
    }

    @Test
    void authorizeEndpointRequiresClientId() {
        given()
          .queryParam("redirect_uri", "https://app.local/callback")
          .when().get("/{tenant}/oauth2/v2.0/authorize", TENANT)
          .then()
            .statusCode(400)
            .body("error", is("invalid_request"));
    }

    @Test
    void authorizationCodeGrantRejectsWrongVerifier() throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest("correct-verifier".getBytes(StandardCharsets.US_ASCII)));

        Response authorize = given()
          .redirects().follow(false)
          .queryParam("client_id", EntraStore.DEV_CLIENT_ID)
          .queryParam("redirect_uri", "https://app.local/callback")
          .queryParam("response_type", "code")
          .queryParam("code_challenge", challenge)
          .queryParam("code_challenge_method", "S256")
          .when().get("/{tenant}/oauth2/v2.0/authorize", TENANT);
        String code = queryParam(authorize.header("Location"), "code");

        given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "authorization_code")
          .formParam("client_id", EntraStore.DEV_CLIENT_ID)
          .formParam("redirect_uri", "https://app.local/callback")
          .formParam("code", code)
          .formParam("code_verifier", "wrong-verifier")
          .when().post("/{tenant}/oauth2/v2.0/token", TENANT)
          .then()
            .statusCode(400)
            .body("error", is("invalid_grant"));
    }

    @Test
    void authorizationCodeGrantRejectsOmittedClientId() {
        Response authorize = given()
          .redirects().follow(false)
          .queryParam("client_id", EntraStore.DEV_CLIENT_ID)
          .queryParam("redirect_uri", "https://app.local/callback")
          .queryParam("response_type", "code")
          .when().get("/{tenant}/oauth2/v2.0/authorize", TENANT);
        String code = queryParam(authorize.header("Location"), "code");

        given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "authorization_code")
          .formParam("redirect_uri", "https://app.local/callback")
          .formParam("code", code)
          .when().post("/{tenant}/oauth2/v2.0/token", TENANT)
          .then()
            .statusCode(400)
            .body("error", is("invalid_grant"));
    }

    @Test
    void authorizationCodeGrantRejectsOmittedRedirectUri() {
        Response authorize = given()
          .redirects().follow(false)
          .queryParam("client_id", EntraStore.DEV_CLIENT_ID)
          .queryParam("redirect_uri", "https://app.local/callback")
          .queryParam("response_type", "code")
          .when().get("/{tenant}/oauth2/v2.0/authorize", TENANT);
        String code = queryParam(authorize.header("Location"), "code");

        given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "authorization_code")
          .formParam("client_id", EntraStore.DEV_CLIENT_ID)
          .formParam("code", code)
          .when().post("/{tenant}/oauth2/v2.0/token", TENANT)
          .then()
            .statusCode(400)
            .body("error", is("invalid_grant"));
    }

    @Test
    void authorizationCodeGrantRejectsCrossTenantRedemption() {
        String otherTenant = "00000000-0000-0000-0000-0000000000ff";
        Response authorize = given()
          .redirects().follow(false)
          .queryParam("client_id", EntraStore.DEV_CLIENT_ID)
          .queryParam("redirect_uri", "https://app.local/callback")
          .queryParam("response_type", "code")
          .when().get("/{tenant}/oauth2/v2.0/authorize", TENANT);
        String code = queryParam(authorize.header("Location"), "code");

        given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "authorization_code")
          .formParam("client_id", EntraStore.DEV_CLIENT_ID)
          .formParam("redirect_uri", "https://app.local/callback")
          .formParam("code", code)
          .when().post("/{tenant}/oauth2/v2.0/token", otherTenant)
          .then()
            .statusCode(400)
            .body("error", is("invalid_grant"));
    }

    private static String queryParam(String url, String name) {
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("query param not found: " + name);
    }

    private static Map<?, ?> decodeJwtClaims(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        return new ObjectMapper().readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
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
