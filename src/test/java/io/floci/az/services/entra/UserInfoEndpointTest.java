package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.SyntheticHttpHeaders;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.core.cloud.ScopeSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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

    @Inject
    UserInfoEndpoint userInfo;

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void discoveryAdvertisesUserInfoAndCodeFlowReturnsTheSameSubjectAsTheIdToken(String method) throws Exception {
        String endpoint = given().get("/" + TENANT + "/v2.0/.well-known/openid-configuration")
                .then().statusCode(200).extract().path("userinfo_endpoint");
        assertEquals(root.resolve("oidc/userinfo").toString(), endpoint);
        Map<String, String> pair = codeTokens("openid profile email");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode idClaims = mapper.readTree(Base64.getUrlDecoder().decode(pair.get("id_token").split("\\.")[1]));
        JsonNode accessClaims = mapper.readTree(Base64.getUrlDecoder().decode(pair.get("access_token").split("\\.")[1]));
        assertEquals(EntraStore.DEV_USER_UPN, idClaims.path("preferred_username").asText());
        assertEquals(EntraStore.DEV_USER_UPN, accessClaims.path("preferred_username").asText());
        given().auth().oauth2(pair.get("access_token")).request(method, endpoint).then().statusCode(200)
                .body("sub", is(idClaims.path("sub").asText()))
                .body("name", is("floci-az dev user"))
                .body("email", is(EntraStore.DEV_USER_UPN))
                .body("$", not(hasKey("preferred_username")));
        given().auth().oauth2(pair.get("id_token")).request(method, endpoint).then().statusCode(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void openidAloneReturnsOnlyTheSubject(String method) {
        String token = codeTokens("openid").get("access_token");
        given().auth().oauth2(token).request(method, "/oidc/userinfo").then().statusCode(200)
                .body("sub", is(EntraStore.DEV_USER_OBJECT_ID))
                .body("$", not(hasKey("name"))).body("$", not(hasKey("email")))
                .body("$", not(hasKey("preferred_username")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void missingOrTamperedTokensAreRejected(String method) {
        given().request(method, "/oidc/userinfo").then().statusCode(401)
                .header("WWW-Authenticate", containsString("Bearer"));
        given().auth().oauth2("not-a-token").request(method, "/oidc/userinfo").then().statusCode(401);
        String valid = codeTokens("openid profile").get("access_token");
        String[] pieces = valid.split("\\.");
        String forged = pieces[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"other\"}".getBytes(StandardCharsets.UTF_8)) + "." + pieces[2];
        given().auth().oauth2(forged).request(method, "/oidc/userinfo").then().statusCode(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void expiredFutureWrongIssuerAndWrongAudienceTokensAreRejected(String method) {
        for (String token : new String[]{issue("openid", -30, issuer(), EntraStore.DEV_CLIENT_ID, null, Map.of()),
                issue("openid", 300, issuer(), EntraStore.DEV_CLIENT_ID, null,
                        Map.of("nbf", System.currentTimeMillis() / 1000 + 3600)),
                issue("openid", 300, "https://wrong.example", EntraStore.DEV_CLIENT_ID, null, Map.of()),
                issue("openid", 300, issuer(), "api://another-resource", null, Map.of())}) {
            given().auth().oauth2(token).request(method, "/oidc/userinfo").then().statusCode(401);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void applicationTokensAndMissingOpenidPermissionAreRejected(String method) {
        given().auth().oauth2(issue("openid profile", 300, issuer(), EntraStore.DEV_CLIENT_ID, "app", Map.of()))
                .request(method, "/oidc/userinfo").then().statusCode(401);
        given().auth().oauth2(codeTokens("profile email").get("access_token"))
                .request(method, "/oidc/userinfo").then().statusCode(403)
                .header("WWW-Authenticate", containsString("insufficient_scope"));
    }

    @Test
    void unsupportedMethodsAdvertiseGetAndPost() {
        given().put("/oidc/userinfo").then().statusCode(405).header("Allow", is("GET, POST"));
    }

    @ParameterizedTest
    @CsvSource({
            "AzureCloud, https://graph.microsoft.com",
            "AzureUSGovernment, https://graph.microsoft.us",
            "AzureChinaCloud, https://microsoftgraph.chinacloudapi.cn"
    })
    void graphAudiencesFollowTheRequestCloud(String cloudName, String graphEndpoint) {
        CloudProfile cloud = CloudCatalog.forName(cloudName).orElseThrow();
        for (String audience : List.of(graphEndpoint, "00000003-0000-0000-c000-000000000000")) {
            assertEquals(200, userInfoStatus(cloud, audience));
        }
        for (CloudProfile other : CloudCatalog.all()) {
            if (!other.endpoints().microsoftGraph().equals(graphEndpoint)) {
                assertEquals(401, userInfoStatus(cloud, other.endpoints().microsoftGraph()));
            }
        }
    }

    private int userInfoStatus(CloudProfile cloud, String audience) {
        String token = issue("openid", 300, issuer(), audience, null, Map.of());
        AzureRequest request = new AzureRequest("GET", null, "entra", "oidc/userinfo",
                new SyntheticHttpHeaders(Map.of("Authorization", "Bearer " + token)), null, Map.of(), null, false)
                .withScope(new ScopeContext(cloud, TENANT, null, null, null, ScopeSource.DEFAULT));
        EntraRequest entra = new EntraRequest(request, "oidc/userinfo", "oidc", new TenantRef(TENANT, TENANT, true),
                root.toString().replaceFirst("/$", ""));
        try (Response response = userInfo.handle(entra)) {
            return response.getStatus();
        }
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
