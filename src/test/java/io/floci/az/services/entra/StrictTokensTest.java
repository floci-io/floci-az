package io.floci.az.services.entra;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestProfile(StrictTokensTest.StrictTokens.class)
@DisplayName("ARM: parity-overrides.strict-tokens refuses a token this emulator would not honour")
class StrictTokensTest {

    public static class StrictTokens implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-tokens", "true");
        }
    }

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String GROUP = "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/strict-tokens-rg";
    private static final String API = "?api-version=2024-03-01";

    private static String tokenFor(String resource) {
        return given().formParam("grant_type", "client_credentials")
            .formParam("client_id", EntraStore.DEV_CLIENT_ID)
            .formParam("client_secret", EntraStore.DEV_CLIENT_SECRET)
            .formParam("scope", resource + "/.default")
            .when().post("/" + TENANT + "/oauth2/v2.0/token")
            .then().statusCode(200).extract().path("access_token");
    }

    private static String ownBaseUrl() {
        return RestAssured.baseURI + ":" + RestAssured.port;
    }

    @Test
    void aTokenForEitherArmAudienceOrTheEmulatorItselfIsServed() {
        for (String resource : new String[] {"https://management.azure.com", "https://management.azure.com/",
            "https://management.core.windows.net", ownBaseUrl(), ownBaseUrl() + "/"}) {
            given().auth().oauth2(tokenFor(resource)).when().get("/subscriptions" + API)
                .then().statusCode(200);
        }
    }

    @Test
    void noAuthorizationHeaderIsAuthenticationFailedWithTheChallenge() {
        given().when().get("/subscriptions" + API)
            .then().statusCode(401)
            .body("error.code", equalTo("AuthenticationFailed"))
            .header("WWW-Authenticate", startsWith("Bearer authorization_uri=\"" + ownBaseUrl() + "/\""))
            .header("WWW-Authenticate", containsString("error=\"invalid_token\""))
            .header("WWW-Authenticate", containsString(
                "error_description=\"The authentication failed because of missing 'Authorization' header.\""));
    }

    @Test
    void aTokenTheEmulatorDidNotSignIsInvalidAuthenticationToken() {
        given().header("Authorization", "Bearer fake-token").when().get("/subscriptions" + API)
            .then().statusCode(401)
            .body("error.code", equalTo("InvalidAuthenticationToken"))
            .header("WWW-Authenticate", containsString("error_description=\"The access token is invalid.\""));
    }

    @Test
    void aTokenForAnotherServiceIsInvalidAuthenticationTokenAudience() {
        given().auth().oauth2(tokenFor("https://vault.azure.net")).when().get("/subscriptions" + API)
            .then().statusCode(401)
            .body("error.code", equalTo("InvalidAuthenticationTokenAudience"))
            .body("error.message", containsString("https://vault.azure.net"));
    }

    @Test
    void aRefusedWriteDoesNotReachItsHandler() {
        String body = "{\"location\":\"eastus\"}";
        given().contentType("application/json").body(body).when().put(GROUP + API).then().statusCode(401);
        given().auth().oauth2(tokenFor("https://management.azure.com")).when().get(GROUP + API)
            .then().statusCode(404);
    }

    @Test
    void aValidTokenRunsTheWholeRequestIncludingItsInternalCalls() {
        String token = tokenFor("https://management.azure.com");
        String template = "{\"properties\":{\"mode\":\"Incremental\",\"template\":{"
            + "\"$schema\":\"https://schema.management.azure.com/schemas/2019-04-01/deploymentTemplate.json#\","
            + "\"contentVersion\":\"1.0.0.0\",\"resources\":[{"
            + "\"type\":\"Microsoft.ManagedIdentity/userAssignedIdentities\",\"apiVersion\":\"2023-01-31\","
            + "\"name\":\"strict-tokens-id\",\"location\":\"eastus\"}]}}}";
        String group = "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/strict-tokens-deploy-rg";
        given().auth().oauth2(token).contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(group + API).then().statusCode(201);

        given().auth().oauth2(token).contentType("application/json").body(template)
            .when().put(group + "/providers/Microsoft.Resources/deployments/strict-tokens" + API)
            .then().statusCode(201);

        // The deployment's resource goes through ArmRouter.dispatchInternal, so it exists only if that call passed.
        given().auth().oauth2(token)
            .when().get(group + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/strict-tokens-id"
                + "?api-version=2023-01-31")
            .then().statusCode(200);
    }

    @Test
    void theEmulatorsOwnEndpointsNeedNoToken() {
        given().when().get("/" + TENANT + "/discovery/v2.0/keys").then().statusCode(200);
        given().when().get("/metadata/endpoints?api-version=2022-09-01").then().statusCode(200);
    }
}
