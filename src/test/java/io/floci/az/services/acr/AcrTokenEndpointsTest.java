package io.floci.az.services.acr;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ACR Entra token exchange as the clients speak it: the bearer challenge, both grant types on
 * each {@code /oauth2/} endpoint, Docker's {@code GET} form, and the error shapes. The protocol is
 * taken from Azure CLI 2.89.1 ({@code azure/cli/command_modules/acr/_docker_utils.py}).
 *
 * <p>Runs in mocked mode: the auth surface needs no Docker. Repository operations do, so here they
 * answer with the registry's {@code UNSUPPORTED} error.</p>
 */
@QuarkusTest
@TestProfile(AcrTokenEndpointsTest.MockedProfile.class)
@DisplayName("ACR: Entra token exchange protocol")
public class AcrTokenEndpointsTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.acr.mocked", "true");
        }
    }

    private static final String REGISTRY = "acrproto";
    private static final String LOGIN_SERVER = REGISTRY + ".azurecr.io";
    private static final String FORM = "application/x-www-form-urlencoded";
    /** Stands in for a token from /oauth2/exchange: not verified, but it must be JWT-shaped. */
    private static final String REFRESH_TOKEN = "header.payload.signature";
    private static final String ARM_REGISTRY =
            "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/test-rg"
                    + "/providers/Microsoft.ContainerRegistry/registries/" + REGISTRY;

    /** The data plane serves a registry that exists, so create it the way a client would. */
    @BeforeEach
    void createTheRegistry() {
        given().when().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json")
                .body("{\"location\":\"eastus\",\"sku\":{\"name\":\"Basic\"},"
                        + "\"properties\":{\"adminUserEnabled\":true}}")
                .when().put(ARM_REGISTRY + "?api-version=2025-11-01")
                .then().statusCode(201);
    }

    // ── Challenge ────────────────────────────────────────────────────────────────

    @Test
    void getV2AnswersTheBearerChallengeThatStartsTheFlow() {
        // The test client speaks plain HTTP, so the realm must too: pointing a client at https://
        // when TLS is off sends it to a port nothing is listening on.
        given().header("Host", LOGIN_SERVER)
                .when().get("/v2/")
                .then().statusCode(401)
                .header("WWW-Authenticate", is("Bearer realm=\"http://" + LOGIN_SERVER
                        + "/oauth2/token\",service=\"" + LOGIN_SERVER + "\""))
                .body("errors[0].code", is("UNAUTHORIZED"));
    }

    @Test
    void theChallengeRealmFollowsAForwardedProto() {
        // A TLS-terminating proxy in front of the emulator: the caller reached it over https, even
        // though this hop is plaintext.
        given().header("Host", LOGIN_SERVER).header("X-Forwarded-Proto", "https")
                .when().get("/v2/")
                .then().statusCode(401)
                .header("WWW-Authenticate", is("Bearer realm=\"https://" + LOGIN_SERVER
                        + "/oauth2/token\",service=\"" + LOGIN_SERVER + "\""));
    }

    @Test
    void theTokenEndpointsExistOnlyOnTheRegistryHost() {
        Response offHost = given().contentType(FORM)
                .formParam("grant_type", "access_token")
                .formParam("service", LOGIN_SERVER)
                .formParam("tenant", "00000000-0000-0000-0000-000000000002")
                .formParam("access_token", "entra-token")
                .when().post("/oauth2/exchange");

        assertNotEquals(200, offHost.statusCode(),
                "the exchange endpoint must not answer on the emulator's own host");
    }

    // ── /oauth2/exchange ─────────────────────────────────────────────────────────

    @Test
    void exchangeTradesAnEntraAccessTokenForARefreshToken() {
        String refreshToken = exchange("access_token").then().statusCode(200)
                .body("refresh_token", notNullValue())
                .extract().path("refresh_token");

        assertEquals(3, refreshToken.split("\\.").length, "the refresh token must be a JWT");
        assertEquals(LOGIN_SERVER, claims(refreshToken).getString("aud"));
    }

    @Test
    void exchangeAcceptsEveryGrantTypeTheClientsSend() {
        exchange("refresh_token").then().statusCode(200).body("refresh_token", notNullValue());
        exchange("access_token_refresh_token").then().statusCode(200).body("refresh_token", notNullValue());
    }

    @Test
    void exchangeRejectsAnUnknownGrantTypeInTheRegistryErrorShape() {
        exchange("client_credentials").then().statusCode(400)
                .body("errors[0].code", is("UNSUPPORTED"))
                .body("errors[0].message", notNullValue());
    }

    @Test
    void exchangeRequiresTheServiceParameter() {
        given().header("Host", LOGIN_SERVER)
                .contentType(FORM)
                .formParam("grant_type", "access_token")
                .formParam("access_token", "entra-token")
                .when().post("/oauth2/exchange")
                .then().statusCode(400)
                .body("errors[0].code", is("UNSUPPORTED"));
    }

    @Test
    void aMalformedFormBodyIsAMalformedRequestNotAServerError() {
        // "%zz" is not a percent escape. The parameter is treated as absent, so both endpoints
        // answer their own 400 in the registry error shape rather than failing to parse.
        given().header("Host", LOGIN_SERVER)
                .contentType(FORM)
                .body("grant_type=access_token&service=%zz")
                .when().post("/oauth2/exchange")
                .then().statusCode(400)
                .body("errors[0].code", is("UNSUPPORTED"));

        given().header("Host", LOGIN_SERVER)
                .contentType(FORM)
                .body("grant_type=refresh_token&refresh_token=" + REFRESH_TOKEN + "&service=%zz")
                .when().post("/oauth2/token")
                .then().statusCode(400)
                .body("errors[0].code", is("UNSUPPORTED"));
    }

    @Test
    void exchangeAcceptsPostOnly() {
        given().header("Host", LOGIN_SERVER)
                .when().get("/oauth2/exchange")
                .then().statusCode(405)
                .body("errors[0].code", is("UNSUPPORTED"));
    }

    // ── /oauth2/token ────────────────────────────────────────────────────────────

    @Test
    void tokenTradesARefreshTokenForAScopedAccessToken() {
        String accessToken = given().header("Host", LOGIN_SERVER)
                .contentType(FORM)
                .formParam("grant_type", "refresh_token")
                .formParam("service", LOGIN_SERVER)
                .formParam("scope", "repository:app:pull,push")
                .formParam("refresh_token", REFRESH_TOKEN)
                .when().post("/oauth2/token")
                .then().statusCode(200)
                .body("expires_in", greaterThan(0))
                .body("issued_at", notNullValue())
                .extract().path("access_token");

        JsonPath claims = claims(accessToken);
        assertEquals(LOGIN_SERVER, claims.getString("aud"));
        assertEquals("repository", claims.getString("access[0].type"));
        assertEquals("app", claims.getString("access[0].name"));
        assertEquals(List.of("pull", "push"), claims.getList("access[0].actions"));
    }

    @Test
    void tokenAcceptsTheRegistryScopeWithoutARepository() {
        String accessToken = tokenForScope("registry:catalog:*");

        JsonPath claims = claims(accessToken);
        assertEquals("registry", claims.getString("access[0].type"));
        assertEquals("catalog", claims.getString("access[0].name"));
    }

    @Test
    void tokenAcceptsAdminCredentialsThroughThePasswordGrant() {
        given().header("Host", LOGIN_SERVER)
                .contentType(FORM)
                .formParam("grant_type", "password")
                .formParam("service", LOGIN_SERVER)
                .formParam("scope", "repository:app:pull")
                .formParam("username", REGISTRY)
                .formParam("password", "an-admin-password")
                .when().post("/oauth2/token")
                .then().statusCode(200)
                .body("access_token", notNullValue());
    }

    @Test
    void tokenAnswersDockersOwnGetForm() {
        String accessToken = given().header("Host", LOGIN_SERVER)
                .queryParam("service", LOGIN_SERVER)
                .queryParam("scope", "repository:app:pull,push")
                .when().get("/oauth2/token")
                .then().statusCode(200)
                .body("expires_in", greaterThan(0))
                .extract().path("access_token");

        assertEquals("app", claims(accessToken).getString("access[0].name"));
    }

    @Test
    void tokenRejectsMalformedRequestsInTheRegistryErrorShape() {
        // Unknown grant type.
        token("authorization_code", Map.of("service", LOGIN_SERVER))
                .then().statusCode(400).body("errors[0].code", is("UNSUPPORTED"));
        // refresh_token grant without a token.
        token("refresh_token", Map.of("service", LOGIN_SERVER))
                .then().statusCode(400).body("errors[0].code", is("UNSUPPORTED"));
        // password grant without a password.
        token("password", Map.of("service", LOGIN_SERVER, "username", REGISTRY))
                .then().statusCode(400).body("errors[0].code", is("UNSUPPORTED"));
        // No service.
        token("refresh_token", Map.of("refresh_token", REFRESH_TOKEN))
                .then().statusCode(400).body("errors[0].code", is("UNSUPPORTED"));
    }

    @Test
    void tokenRejectsARefreshTokenThatIsNotEvenAJwt() {
        // Nothing verifies the token, but a malformed one still gets the 401 that makes a client
        // re-authenticate rather than an access token it cannot use.
        token("refresh_token", Map.of("service", LOGIN_SERVER, "refresh_token", "not-a-jwt"))
                .then().statusCode(401).body("errors[0].code", is("UNAUTHORIZED"));
    }

    // ── Repository operations ────────────────────────────────────────────────────

    @Test
    void repositoryOperationsReportUnsupportedInMockedMode() {
        given().header("Host", LOGIN_SERVER).header("Authorization", "Bearer a-token")
                .when().get("/v2/app/tags/list")
                .then().statusCode(405)
                .body("errors[0].code", is("UNSUPPORTED"));

        given().header("Host", LOGIN_SERVER).header("Authorization", "Bearer a-token")
                .when().get("/v2/")
                .then().statusCode(405)
                .body("errors[0].code", is("UNSUPPORTED"));
    }

    @Test
    void unknownPathsOnTheRegistryHostUseTheRegistryErrorShape() {
        given().header("Host", LOGIN_SERVER)
                .when().get("/v1/repositories")
                .then().statusCode(404)
                .body("errors[0].code", is("NAME_UNKNOWN"));
    }

    @Test
    void aRegistryThatWasNeverCreatedIsNotServed() {
        // The hosts entry points every *.azurecr.io name at the emulator, so a name nobody created
        // must be refused rather than handed tokens and a repository prefix of its own.
        String unknown = "acrneverexisted.azurecr.io";

        given().header("Host", unknown)
                .when().get("/v2/")
                .then().statusCode(404)
                .body("errors[0].code", is("NAME_UNKNOWN"));

        given().header("Host", unknown).contentType(FORM)
                .formParam("grant_type", "access_token")
                .formParam("service", unknown)
                .when().post("/oauth2/exchange")
                .then().statusCode(404)
                .body("errors[0].code", is("NAME_UNKNOWN"));

        given().header("Host", unknown).contentType(FORM)
                .formParam("grant_type", "refresh_token")
                .formParam("service", unknown)
                .formParam("refresh_token", REFRESH_TOKEN)
                .when().post("/oauth2/token")
                .then().statusCode(404)
                .body("errors[0].code", is("NAME_UNKNOWN"));
    }

    @Test
    void deletingTheRegistryTakesItsDataPlaneWithIt() {
        given().when().delete(ARM_REGISTRY + "?api-version=2025-11-01")
                .then().statusCode(anyOf(is(200), is(202), is(204)));

        given().header("Host", LOGIN_SERVER)
                .when().get("/v2/")
                .then().statusCode(404)
                .body("errors[0].code", is("NAME_UNKNOWN"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static Response exchange(String grantType) {
        return given().header("Host", LOGIN_SERVER)
                .contentType(FORM)
                .formParam("grant_type", grantType)
                .formParam("service", LOGIN_SERVER)
                .formParam("tenant", "00000000-0000-0000-0000-000000000002")
                .formParam("access_token", "an-entra-token")
                .when().post("/oauth2/exchange");
    }

    private static Response token(String grantType, Map<String, String> form) {
        var request = given().header("Host", LOGIN_SERVER).contentType(FORM)
                .formParam("grant_type", grantType);
        form.forEach(request::formParam);
        return request.when().post("/oauth2/token");
    }

    private static String tokenForScope(String scope) {
        return given().header("Host", LOGIN_SERVER)
                .contentType(FORM)
                .formParam("grant_type", "refresh_token")
                .formParam("service", LOGIN_SERVER)
                .formParam("scope", scope)
                .formParam("refresh_token", REFRESH_TOKEN)
                .when().post("/oauth2/token")
                .then().statusCode(200)
                .extract().path("access_token");
    }

    /** The token's claims: clients decode these without verifying the signature. */
    private static JsonPath claims(String jwt) {
        String[] parts = jwt.split("\\.");
        assertTrue(parts.length == 3, "expected a compact JWT, got: " + jwt);
        return new JsonPath(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }
}
