package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(PasswordGrantIntegrationTest.Enforced.class)
class PasswordGrantIntegrationTest {
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String TOKEN = "/" + TENANT + "/oauth2/v2.0/token";
    private static final String PASSWORD = "isolated-ROPC-test-password";
    private static final String SCOPE = "openid profile email api://resource/read";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject GraphDirectoryStore directory;

    private DirectoryObject user;

    public static class Enforced implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Leave client validation off: user validation must work independently.
            return Map.of("floci-az.services.entra.validate-user-credentials", "true",
                    "floci-az.services.entra.token-lifetime-seconds", "2");
        }
    }

    @BeforeEach
    void provisionAUserWhoseObjectIdIsIndependentOfThePrincipalName() {
        String id = UUID.randomUUID().toString();
        user = GraphDirectoryStore.user(TENANT, id, id + "@local.invalid", "Directory password user",
                id + "@local.invalid");
        user.props().put("accountEnabled", true);
        user.props().putObject("passwordProfile").put("password", PASSWORD)
                .put("forceChangePasswordNextSignIn", false);
        directory.put(user);
    }

    @AfterEach
    void removeTheFixture() {
        directory.delete(TENANT, DirectoryEntitySet.USERS, user.id());
    }

    private static RequestSpecification client() {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("client_id", EntraStore.DEV_CLIENT_ID);
    }

    private RequestSpecification passwordRequest(String password, String scope) {
        return client().formParam("grant_type", "password").formParam("username", user.text("userPrincipalName"))
                .formParam("password", password).formParam("scope", scope);
    }

    private Response password() {
        return passwordRequest(PASSWORD, SCOPE).post(TOKEN);
    }

    private static Response refresh(String token) {
        return client().formParam("grant_type", "refresh_token").formParam("refresh_token", token).post(TOKEN);
    }

    private static JsonNode claims(String token) throws Exception {
        return MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private Response browser(String scope) {
        String redirect = "https://local.invalid/callback";
        String location = given().redirects().follow(false).queryParam("client_id", EntraStore.DEV_CLIENT_ID)
                .queryParam("redirect_uri", redirect).queryParam("response_type", "code")
                .queryParam("scope", scope).queryParam("login_hint", user.text("userPrincipalName"))
                .get("/" + TENANT + "/oauth2/v2.0/authorize").then().statusCode(302).extract().header("Location");
        String code = URI.create(location).getQuery().substring("code=".length());
        return client().formParam("grant_type", "authorization_code").formParam("code", code)
                .formParam("redirect_uri", redirect).post(TOKEN);
    }

    @Test
    void wrongPasswordsAreRejectedWhenUserValidationIsEnabled() {
        passwordRequest("wrong-password", SCOPE).post(TOKEN).then().statusCode(400)
                .body("error", is("invalid_grant")).body("error_codes", contains(50126));
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "password", "client_id"})
    void missingOrBlankRequiredFieldsCannotUseDevelopmentDefaults(String field) {
        Map<String, String> form = new HashMap<>(Map.of("grant_type", "password",
                "username", user.text("userPrincipalName"), "password", PASSWORD,
                "client_id", EntraStore.DEV_CLIENT_ID));
        form.remove(field);
        given().contentType("application/x-www-form-urlencoded").formParams(form).post(TOKEN)
                .then().statusCode(400).body("error", is("invalid_request"))
                .body("error_codes", contains(900144)).body("error_description", containsString("'" + field + "'"));
        form.put(field, " ");
        given().contentType("application/x-www-form-urlencoded").formParams(form).post(TOKEN)
                .then().statusCode(400).body("error_codes", contains(900144));
    }

    @Test
    void basicClientIdentificationAndOmittedStatusFlagsAreSupported() throws Exception {
        user.props().remove("accountEnabled");
        user.props().withObject("passwordProfile").remove("forceChangePasswordNextSignIn");
        directory.put(user);
        Response response = given().auth().preemptive().basic(EntraStore.DEV_CLIENT_ID, EntraStore.DEV_CLIENT_SECRET)
                .contentType("application/x-www-form-urlencoded").formParam("grant_type", "password")
                .formParam("username", user.text("userPrincipalName")).formParam("password", PASSWORD)
                .formParam("scope", SCOPE).post("/organizations/oauth2/v2.0/token")
                .then().statusCode(200).extract().response();
        assertEquals(user.id(), claims(response.path("access_token")).path("oid").asText());
        assertEquals(TENANT, claims(response.path("access_token")).path("tid").asText());
    }

    @Test
    void unknownUsersAndMissingOrMalformedHashesCannotAuthenticate() {
        client().formParam("grant_type", "password").formParam("username", "unknown@local.invalid")
                .formParam("password", PASSWORD).post(TOKEN).then().statusCode(400)
                .body("error_codes", contains(50126));
        client().formParam("grant_type", "password").formParam("username", EntraStore.DEV_USER_UPN)
                .formParam("password", PASSWORD).post(TOKEN).then().statusCode(400)
                .body("error_codes", contains(50126));
        user.props().putObject("passwordProfile").putObject("secretHash").put("iterations", 0);
        directory.put(user);
        password().then().statusCode(400).body("error_codes", contains(50126));
        user.props().putObject("passwordProfile").put("secretHash", "unreadable-hash");
        directory.put(user);
        password().then().statusCode(400).body("error_codes", contains(50126));
    }

    @Test
    void anUnregisteredTenantCannotAuthenticateAgainstTheDefaultDirectory() {
        passwordRequest(PASSWORD, SCOPE).post("/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/oauth2/v2.0/token")
                .then().statusCode(400).body("error_codes", contains(50126));
    }

    @Test
    void disabledAccountsCannotAuthenticate() {
        user.props().put("accountEnabled", false);
        directory.put(user);
        password().then().statusCode(400).body("error", is("invalid_grant"))
                .body("error_codes", contains(50057));
    }

    @ParameterizedTest
    @ValueSource(strings = {"forceChangePasswordNextSignIn", "forceChangePasswordNextSignInWithMfa"})
    void accountsRequiringAPasswordChangeCannotAuthenticate(String flag) {
        user.props().withObject("passwordProfile").put(flag, true);
        directory.put(user);
        password().then().statusCode(400).body("error", is("invalid_grant"))
                .body("error_codes", contains(50055));
    }

    @Test
    void passwordAndBrowserGrantsIdentifyTheSameUserAndGraphMemberships() throws Exception {
        String groupId = UUID.randomUUID().toString();
        directory.put(GraphDirectoryStore.group(TENANT, groupId, "Password test group", true));
        directory.addLink(TENANT, groupId, GraphDirectoryStore.MEMBERS, user.id());
        try {
            Response ropc = client().formParam("grant_type", "password")
                    .formParam("username", user.text("userPrincipalName").toUpperCase())
                    .formParam("password", PASSWORD).formParam("scope", SCOPE).formParam("client_info", "1")
                    .post(TOKEN).then().statusCode(200).extract().response();
            Response code = browser(SCOPE).then().statusCode(200).extract().response();
            for (Response response : new Response[]{ropc, code}) {
                JsonNode access = claims(response.path("access_token"));
                JsonNode id = claims(response.path("id_token"));
                assertEquals(user.id(), access.path("oid").asText());
                assertEquals(user.id(), access.path("sub").asText());
                assertEquals(TENANT, access.path("tid").asText());
                assertEquals(user.text("userPrincipalName"), access.path("preferred_username").asText());
                assertEquals(user.id(), id.path("oid").asText());
                assertEquals(user.id(), id.path("sub").asText());
                given().auth().oauth2(response.path("access_token")).get("/oidc/userinfo").then().statusCode(200)
                        .body("sub", is(user.id())).body("name", is(user.text("displayName")));
                given().auth().oauth2(response.path("access_token")).contentType("application/json").body("{}")
                        .post("/v1.0/users/" + access.path("oid").asText() + "/getMemberGroups")
                        .then().statusCode(200).body("value", contains(groupId));
            }
            JsonNode info = MAPPER.readTree(Base64.getUrlDecoder().decode(ropc.<String>path("client_info")));
            assertEquals(user.id(), info.path("uid").asText());
            given().queryParam("$filter", "id eq '" + groupId + "'").queryParam("$expand", "members")
                    .get("/v1.0/groups").then().statusCode(200)
                    .body("value[0].members[0].id", is(user.id()))
                    .body("value[0].members[0]", not(hasKey("passwordProfile")));
        } finally {
            directory.delete(TENANT, DirectoryEntitySet.GROUPS, groupId);
        }
    }

    @Test
    void onlyExplicitOfflineAccessAtTheV2EndpointIssuesRefreshTokens() throws Exception {
        password().then().statusCode(200).body("refresh_token", nullValue());
        passwordRequest(PASSWORD, SCOPE + " api://resource/offline_access").post(TOKEN)
                .then().statusCode(200).body("refresh_token", nullValue());
        passwordRequest(PASSWORD, SCOPE + " offline_access").post(TOKEN).then().statusCode(200)
                .body("refresh_token", not(emptyOrNullString()));
        passwordRequest(PASSWORD, "offline_access").post(TOKEN).then().statusCode(200)
                .body("refresh_token", not(emptyOrNullString())).body("id_token", nullValue());
        Response v1 = passwordRequest(PASSWORD, SCOPE + " offline_access").post("/" + TENANT + "/oauth2/token")
                .then().statusCode(200).body("refresh_token", nullValue()).extract().response();
        assertEquals(user.id(), claims(v1.path("access_token")).path("oid").asText());
        assertEquals("1.0", claims(v1.path("access_token")).path("ver").asText());
    }

    @Test
    void aPasswordSessionRenewsAfterItsAccessTokenExpires() throws Exception {
        Response original = passwordRequest(PASSWORD, SCOPE + " offline_access").post(TOKEN)
                .then().statusCode(200).extract().response();
        String access = original.path("access_token");
        long expires = claims(access).path("exp").asLong();
        long waitMillis = Math.max(0, Instant.ofEpochSecond(expires).toEpochMilli() - System.currentTimeMillis() + 50);
        assertTrue(waitMillis < 5000, "the test profile must issue short-lived tokens");
        Thread.sleep(waitMillis);
        given().auth().oauth2(access).get("/oidc/userinfo").then().statusCode(401);

        String originalRefresh = original.path("refresh_token");
        Response renewed = refresh(originalRefresh).then().statusCode(200)
                .body("refresh_token", not(is(originalRefresh))).extract().response();
        JsonNode after = claims(renewed.path("access_token"));
        assertEquals(user.id(), after.path("oid").asText());
        assertEquals(user.id(), after.path("sub").asText());
        assertEquals(TENANT, after.path("tid").asText());
        assertTrue(after.path("exp").asLong() > expires);
        assertEquals(user.id(), claims(renewed.path("id_token")).path("sub").asText());
        given().auth().oauth2(renewed.path("access_token")).get("/oidc/userinfo").then().statusCode(200)
                .body("sub", is(user.id()));
        refresh(originalRefresh).then().statusCode(200);
        password().then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "forceChangePasswordNextSignIn", "forceChangePasswordNextSignInWithMfa", "deleted"})
    void accountStatusChangesRejectPasswordAndBrowserRefreshSessions(String status) {
        String passwordRefresh = passwordRequest(PASSWORD, SCOPE + " offline_access").post(TOKEN)
                .then().statusCode(200).extract().path("refresh_token");
        String browserRefresh = browser(SCOPE + " offline_access").then().statusCode(200)
                .extract().path("refresh_token");
        if ("deleted".equals(status)) {
            directory.delete(TENANT, DirectoryEntitySet.USERS, user.id());
        } else {
            if ("disabled".equals(status)) {
                user.props().put("accountEnabled", false);
            } else {
                user.props().withObject("passwordProfile").put(status, true);
            }
            directory.put(user);
        }
        for (String token : new String[]{passwordRefresh, browserRefresh}) {
            refresh(token).then().statusCode(400).body("error", is("invalid_grant"))
                    .body("error_codes", contains(70000));
        }
    }
}
