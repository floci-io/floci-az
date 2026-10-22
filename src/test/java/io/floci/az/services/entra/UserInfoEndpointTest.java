package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class UserInfoEndpointTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";

    @TestHTTPResource("/")
    URI root;

    @Inject
    TokenIssuer tokens;

    @Test
    void discoveryAdvertisesUserInfoAndCodeFlowReturnsTheSameSubjectAsTheIdToken() throws Exception {
        String endpoint = given().get("/" + TENANT + "/v2.0/.well-known/openid-configuration")
                .then().statusCode(200).extract().path("userinfo_endpoint");
        assertEquals(root.resolve("oidc/userinfo").toString(), endpoint);
        Map<String, String> pair = codeTokens("openid profile email");
        String subject = new ObjectMapper().readTree(Base64.getUrlDecoder()
                .decode(pair.get("id_token").split("\\.")[1])).path("sub").asText();
        given().auth().oauth2(pair.get("access_token")).get(endpoint).then().statusCode(200)
                .body("sub", is(subject))
                .body("name", is("floci-az dev user"))
                .body("email", is(EntraStore.DEV_USER_UPN))
                .body("preferred_username", is(EntraStore.DEV_USER_UPN));
        given().auth().oauth2(pair.get("id_token")).get(endpoint).then().statusCode(403);
    }

    @Test
    void openidAloneReturnsOnlyTheSubject() {
        String token = codeTokens("openid").get("access_token");
        given().auth().oauth2(token).get("/oidc/userinfo").then().statusCode(200)
                .body("sub", is(EntraStore.DEV_USER_OBJECT_ID))
                .body("$", not(hasKey("name"))).body("$", not(hasKey("email")))
                .body("$", not(hasKey("preferred_username")));
    }

    @Test
    void missingOrTamperedTokensAreRejected() {
        given().get("/oidc/userinfo").then().statusCode(401).header("WWW-Authenticate", containsString("Bearer"));
        given().auth().oauth2("not-a-token").get("/oidc/userinfo").then().statusCode(401);
        String valid = codeTokens("openid profile").get("access_token");
        String[] pieces = valid.split("\\.");
        String forged = pieces[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"other\"}".getBytes(StandardCharsets.UTF_8)) + "." + pieces[2];
        given().auth().oauth2(forged).get("/oidc/userinfo").then().statusCode(401);
    }

    @Test
    void expiredFutureWrongIssuerAndWrongAudienceTokensAreRejected() {
        for (String token : new String[]{issue("openid", -30, issuer(), EntraStore.DEV_CLIENT_ID, null, Map.of()),
                issue("openid", 300, issuer(), EntraStore.DEV_CLIENT_ID, null,
                        Map.of("nbf", System.currentTimeMillis() / 1000 + 3600)),
                issue("openid", 300, "https://wrong.example", EntraStore.DEV_CLIENT_ID, null, Map.of()),
                issue("openid", 300, issuer(), "api://another-resource", null, Map.of())}) {
            given().auth().oauth2(token).get("/oidc/userinfo").then().statusCode(401);
        }
    }

    @Test
    void applicationTokensAndMissingOpenidPermissionAreRejected() {
        given().auth().oauth2(issue("openid profile", 300, issuer(), EntraStore.DEV_CLIENT_ID, "app", Map.of()))
                .get("/oidc/userinfo").then().statusCode(401);
        given().auth().oauth2(codeTokens("profile email").get("access_token"))
                .get("/oidc/userinfo").then().statusCode(403)
                .header("WWW-Authenticate", containsString("insufficient_scope"));
    }

    private String issuer() {
        return root.resolve(TENANT + "/v2.0").toString();
    }

    private String issue(String scope, long lifetime, String issuer, String audience, String identity,
                         Map<String, Object> extra) {
        return tokens.issue(new TokenIssuer.TokenSpec(TENANT, issuer, audience, EntraStore.DEV_USER_OBJECT_ID,
                EntraStore.DEV_USER_OBJECT_ID, EntraStore.DEV_CLIENT_ID, scope, "2.0", identity, lifetime), extra);
    }

    private Map<String, String> codeTokens(String scope) {
        String callback = "https://client.example/callback";
        String location = given().redirects().follow(false).queryParam("client_id", EntraStore.DEV_CLIENT_ID)
                .queryParam("redirect_uri", callback).queryParam("response_type", "code").queryParam("scope", scope)
                .get("/" + TENANT + "/oauth2/v2.0/authorize").then().statusCode(302).extract().header("Location");
        String code = URLDecoder.decode(URI.create(location).getRawQuery().split("code=")[1].split("&")[0],
                StandardCharsets.UTF_8);
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "authorization_code").formParam("client_id", EntraStore.DEV_CLIENT_ID)
                .formParam("redirect_uri", callback).formParam("code", code)
                .post("/" + TENANT + "/oauth2/v2.0/token").then().statusCode(200).extract().jsonPath().getMap("$");
    }
}
