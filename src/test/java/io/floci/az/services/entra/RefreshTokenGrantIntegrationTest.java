package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.entra.EntraModels.RefreshToken;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(RefreshTokenGrantIntegrationTest.ShortTokens.class)
class RefreshTokenGrantIntegrationTest {
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String TOKEN = "/" + TENANT + "/oauth2/v2.0/token";
    private static final String REDIRECT = "https://app.local/callback";
    private static final String SCOPE = "openid profile email offline_access api://resource/read";
    private static final String VERIFIER = "a-valid-pkce-verifier-with-at-least-43-characters-long";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntraStore store;

    @Inject
    GraphDirectoryStore directory;

    public static class ShortTokens implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.entra.validate-client-credentials", "true",
                    "floci-az.services.entra.token-lifetime-seconds", "2");
        }
    }

    private static RequestSpecification tokenRequest(boolean secret) {
        return tokenRequest(EntraStore.DEV_CLIENT_ID, secret);
    }

    private static RequestSpecification tokenRequest(String clientId, boolean secret) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .formParam("client_id", clientId);
        return secret ? request.formParam("client_secret", EntraStore.DEV_CLIENT_SECRET) : request;
    }

    private static Response exchange(String scope, boolean secret) throws Exception {
        return exchange(scope, secret, EntraStore.DEV_CLIENT_ID);
    }

    private static Response exchange(String scope, boolean secret, String clientId) throws Exception {
        return codeExchange(scope, secret, clientId).then().statusCode(200).extract().response();
    }

    private static Response codeExchange(String scope, boolean secret, String clientId) throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        String location = given().redirects().follow(false)
                .queryParam("client_id", clientId).queryParam("redirect_uri", REDIRECT)
                .queryParam("response_type", "code").queryParam("scope", scope).queryParam("nonce", "original-nonce")
                .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256")
                .get("/" + TENANT + "/oauth2/v2.0/authorize").then().statusCode(302).extract().header("Location");
        String code = URI.create(location).getQuery().substring("code=".length());
        return tokenRequest(clientId, secret).formParam("grant_type", "authorization_code").formParam("code", code)
                .formParam("redirect_uri", REDIRECT).formParam("code_verifier", VERIFIER)
                .post(TOKEN);
    }

    private static Response refresh(String token, boolean secret) {
        return tokenRequest(secret).formParam("grant_type", "refresh_token").formParam("refresh_token", token)
                .post(TOKEN);
    }

    private static JsonNode claims(String token) throws Exception {
        return MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    @Test
    void offlineAccessIssuesARefreshTokenAndMissingOfflineAccessDoesNot() throws Exception {
        exchange("openid profile", false).then().body("refresh_token", nullValue());
        exchange(SCOPE, false).then().body("refresh_token", not(emptyOrNullString()));
        exchange("offline_access", false).then().body("refresh_token", not(emptyOrNullString()))
                .body("id_token", nullValue());
    }

    @Test
    void refreshRenewsAnExpiredAccessTokenForTheSameUser() throws Exception {
        Response original = exchange(SCOPE, true);
        String access = original.path("access_token");
        String originalRefresh = original.path("refresh_token");
        JsonNode before = claims(access);
        Instant expires = Instant.ofEpochSecond(before.path("exp").asLong());
        long waitMillis = Math.max(0, expires.toEpochMilli() - System.currentTimeMillis() + 50);
        assertTrue(waitMillis < 5000, "the test profile must issue short-lived access tokens");
        Thread.sleep(waitMillis);
        given().auth().oauth2(access).get("/oidc/userinfo").then().statusCode(401);

        Response renewed = refresh(originalRefresh, true).then().statusCode(200)
                .body("token_type", is("Bearer")).body("expires_in", is(2))
                .body("refresh_token", not(is(originalRefresh)))
                .extract().response();
        JsonNode after = claims(renewed.path("access_token"));
        assertEquals(before.path("sub"), after.path("sub"));
        assertEquals(before.path("oid"), after.path("oid"));
        assertEquals(before.path("tid"), after.path("tid"));
        assertNotEquals(before.path("uti"), after.path("uti"));
        assertTrue(after.path("exp").asLong() > before.path("exp").asLong());
        assertEquals(claims(original.path("id_token")).path("sub"), claims(renewed.path("id_token")).path("sub"));
        assertFalse(claims(renewed.path("id_token")).has("nonce"));
        given().auth().oauth2(renewed.path("access_token")).get("/oidc/userinfo").then().statusCode(200)
                .body("sub", is(before.path("sub").asText()));
    }

    @Test
    void oldAndReplacementRefreshTokensRemainUsable() throws Exception {
        String original = exchange(SCOPE, false).path("refresh_token");
        String replacement = refresh(original, false).then().statusCode(200).extract().path("refresh_token");
        assertNotEquals(original, replacement);
        refresh(original, false).then().statusCode(200);
        refresh(replacement, false).then().statusCode(200);
    }

    @Test
    void missingAndUnknownRefreshTokensHaveOAuthErrors() {
        tokenRequest(false).formParam("grant_type", "refresh_token").post(TOKEN).then().statusCode(400)
                .body("error", is("invalid_request")).body("error_codes", contains(900144));
        refresh("invalid-standalone-repro-token", false).then().statusCode(400)
                .body("error", is("invalid_grant")).body("error_codes", contains(70000));
    }

    @Test
    void aNamedTenantCannotRedeemAnotherTenantsRefreshToken() throws Exception {
        String token = exchange(SCOPE, false).path("refresh_token");
        tokenRequest(false).formParam("grant_type", "refresh_token").formParam("refresh_token", token)
                .post("/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/oauth2/v2.0/token").then().statusCode(400)
                .body("error", is("invalid_grant"));
        tokenRequest(false).formParam("grant_type", "refresh_token").formParam("refresh_token", token)
                .post("/organizations/oauth2/v2.0/token").then().statusCode(200);
    }

    @Test
    void scopesCanBeNarrowedButCannotGainNewPermissions() throws Exception {
        String token = exchange(SCOPE, false).path("refresh_token");
        Response narrowed = tokenRequest(false).formParam("grant_type", "refresh_token").formParam("refresh_token", token)
                .formParam("scope", "api://resource/read").post(TOKEN).then().statusCode(200)
                .body("id_token", nullValue()).body("scope", is("api://resource/read")).extract().response();
        assertEquals("api://resource/read", claims(narrowed.path("access_token")).path("scp").asText());
        tokenRequest(false).formParam("grant_type", "refresh_token").formParam("refresh_token", token)
                .formParam("scope", "api://resource/write").post(TOKEN).then().statusCode(400)
                .body("error", is("invalid_scope")).body("error_codes", contains(70011));
        refresh(narrowed.path("refresh_token"), false).then().statusCode(200).body("id_token", notNullValue());
    }

    @Test
    void confidentialClientsNeedCredentialsAndPublicClientsDoNot() throws Exception {
        String confidential = exchange(SCOPE, true).path("refresh_token");
        refresh(confidential, false).then().statusCode(401).body("error", is("invalid_client"))
                .body("error_codes", contains(7000218));
        given().contentType("application/x-www-form-urlencoded").formParam("grant_type", "refresh_token")
                .formParam("client_id", EntraStore.DEV_CLIENT_ID).formParam("client_secret", "wrong-secret")
                .formParam("refresh_token", confidential).post(TOKEN).then().statusCode(401)
                .body("error_codes", contains(7000215));
        given().auth().preemptive().basic(EntraStore.DEV_CLIENT_ID, EntraStore.DEV_CLIENT_SECRET)
                .contentType("application/x-www-form-urlencoded").formParam("grant_type", "refresh_token")
                .formParam("refresh_token", confidential).post(TOKEN).then().statusCode(200);
        refresh(exchange(SCOPE, false).path("refresh_token"), false).then().statusCode(200);
    }

    @Test
    void expiryRevocationAndMissingUsersAreRejected() {
        for (boolean spa : new boolean[]{false, true}) {
            String expired = store.issueRefreshToken(new RefreshToken(TENANT, TENANT, EntraStore.DEV_CLIENT_ID,
                    EntraStore.DEV_USER_OBJECT_ID, SCOPE, false, spa, Instant.now().minusSeconds(1)));
            refresh(expired, false).then().statusCode(400).body("error", is("invalid_grant"))
                    .body("error_codes", contains(spa ? 700084 : 700082));
        }
        String revoked = store.issueRefreshToken(new RefreshToken(TENANT, TENANT, EntraStore.DEV_CLIENT_ID,
                EntraStore.DEV_USER_OBJECT_ID, SCOPE, false, false, Instant.now().plusSeconds(3600)));
        store.revokeRefreshToken(revoked);
        refresh(revoked, false).then().statusCode(400).body("error", is("invalid_grant"));
        String deletedUser = store.issueRefreshToken(new RefreshToken(TENANT, TENANT, EntraStore.DEV_CLIENT_ID,
                "no-longer-present", SCOPE, false, false, Instant.now().plusSeconds(3600)));
        refresh(deletedUser, false).then().statusCode(400).body("error", is("invalid_grant"));
    }

    @Test
    void anotherRegisteredClientCannotRedeemTheToken() throws Exception {
        String client = UUID.randomUUID().toString();
        DirectoryObject app = GraphDirectoryStore.application(TENANT, UUID.randomUUID().toString(), client,
                "Other public client", List.of());
        directory.put(app);
        try {
            String token = exchange(SCOPE, false).path("refresh_token");
            tokenRequest(client, false).formParam("grant_type", "refresh_token").formParam("refresh_token", token)
                    .post(TOKEN).then().statusCode(400).body("error", is("invalid_grant"));
            refresh(token, false).then().statusCode(200);
        } finally {
            directory.delete(TENANT, DirectoryEntitySet.APPLICATIONS, app.id());
        }
    }

    @Test
    void spaReplacementsRetainTheirOriginalTwentyFourHourDeadline() throws Exception {
        String client = UUID.randomUUID().toString();
        DirectoryObject app = GraphDirectoryStore.application(TENANT, UUID.randomUUID().toString(), client,
                "SPA client", List.of());
        app.props().putObject("spa").putArray("redirectUris").add(REDIRECT);
        directory.put(app);
        try {
            String token = exchange(SCOPE, false, client).path("refresh_token");
            RefreshToken initial = store.getRefreshToken(token).orElseThrow();
            assertTrue(initial.spa());
            assertTrue(initial.expiresAt().isAfter(Instant.now().plus(Duration.ofHours(23))));
            assertTrue(initial.expiresAt().isBefore(Instant.now().plus(Duration.ofHours(25))));
            String replacement = tokenRequest(client, false).formParam("grant_type", "refresh_token")
                    .formParam("refresh_token", token).post(TOKEN).then().statusCode(200).extract().path("refresh_token");
            assertEquals(initial.expiresAt(), store.getRefreshToken(replacement).orElseThrow().expiresAt());
        } finally {
            directory.delete(TENANT, DirectoryEntitySet.APPLICATIONS, app.id());
        }
    }

    @Test
    void nonSpaReplacementHasANewNinetyDayLifetime() {
        String token = store.issueRefreshToken(new RefreshToken(TENANT, TENANT, EntraStore.DEV_CLIENT_ID,
                EntraStore.DEV_USER_OBJECT_ID, SCOPE, false, false, Instant.now().plusSeconds(3600)));
        String replacement = refresh(token, false).then().statusCode(200).extract().path("refresh_token");
        Instant expiry = store.getRefreshToken(replacement).orElseThrow().expiresAt();
        assertTrue(expiry.isAfter(Instant.now().plus(Duration.ofDays(89))));
        assertTrue(expiry.isBefore(Instant.now().plus(Duration.ofDays(91))));
    }

    @Test
    void aRegisteredWebRedirectRequiresConfidentialClientAuthentication() throws Exception {
        String client = UUID.randomUUID().toString();
        DirectoryObject app = GraphDirectoryStore.application(TENANT, UUID.randomUUID().toString(), client,
                "Web client", List.of(MAPPER.createObjectNode().put("keyId", "test")
                        .put(GraphDirectoryStore.SECRET_TEXT, EntraStore.DEV_CLIENT_SECRET)));
        app.props().putObject("web").putArray("redirectUris").add(REDIRECT);
        directory.put(app);
        try {
            codeExchange(SCOPE, false, client).then().statusCode(401).body("error_codes", contains(7000218));
            String token = exchange(SCOPE, true, client).path("refresh_token");
            tokenRequest(client, false).formParam("grant_type", "refresh_token").formParam("refresh_token", token)
                    .post(TOKEN).then().statusCode(401).body("error_codes", contains(7000218));
            tokenRequest(client, true).formParam("grant_type", "refresh_token").formParam("refresh_token", token)
                    .post(TOKEN).then().statusCode(200);
        } finally {
            directory.delete(TENANT, DirectoryEntitySet.APPLICATIONS, app.id());
        }
    }
}
