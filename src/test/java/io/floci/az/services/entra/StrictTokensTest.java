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
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(StrictTokensTest.StrictTokens.class)
@DisplayName("ARM: parity-overrides.strict-tokens refuses a token this emulator would not honour")
class StrictTokensTest {

    public static class StrictTokens implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-tokens", "true",
                "floci-az.tenants[0].id", OTHER_TENANT,
                "floci-az.tenants[0].display-name", "Other tenant",
                "floci-az.tenants[0].default-domain", "other.example",
                "floci-az.subscriptions[0].id", OTHER_SUBSCRIPTION,
                "floci-az.subscriptions[0].tenant-id", OTHER_TENANT);
        }
    }

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER_TENANT = "77777777-0000-0000-0000-000000000000";
    private static final String OTHER_SUBSCRIPTION = "77777777-1111-0000-0000-000000000000";
    private static final String GROUP = "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/strict-tokens-rg";
    private static final String API = "?api-version=2024-03-01";

    /** A deployment answers 201 and runs afterwards, so its resources exist only once it has ended. */
    private static String awaitDeployment(String token, String deployment) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            String state = given().auth().oauth2(token).when().get(deployment + API)
                .then().statusCode(200).extract().path("properties.provisioningState");
            if (!state.equals("Accepted") && !state.equals("Running")) {
                return state;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the deployment did not finish: " + deployment);
    }

    private static String tokenFor(String resource) {
        return tokenFor(TENANT, resource);
    }

    private static String tokenFor(String tenant, String resource) {
        return given().formParam("grant_type", "client_credentials")
            .formParam("client_id", EntraStore.DEV_CLIENT_ID)
            .formParam("client_secret", EntraStore.DEV_CLIENT_SECRET)
            .formParam("scope", resource + "/.default")
            .when().post("/" + tenant + "/oauth2/v2.0/token")
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
    void namingGraphAsTheHostDoesNotMakeAGraphTokenAnArmToken() {
        given().auth().oauth2(tokenFor("https://graph.microsoft.com"))
            .header("Host", "graph.microsoft.com")
            .header("X-Forwarded-Proto", "https")
            .when().get("/subscriptions" + API)
            .then().statusCode(401)
            .body("error.code", equalTo("InvalidAuthenticationTokenAudience"));
    }

    @Test
    void aHostNameTheEmulatorIsReachedThroughStaysAnAudience() {
        given().auth().oauth2(tokenFor("http://floci-az:4577"))
            .header("Host", "floci-az:4577")
            .when().get("/subscriptions" + API)
            .then().statusCode(200);
    }

    @Test
    void anArmTokenStillWorksWhenTheEmulatorAnswersForTheArmHost() {
        given().auth().oauth2(tokenFor("https://management.azure.com"))
            .header("Host", "management.azure.com")
            .header("X-Forwarded-Proto", "https")
            .when().get("/subscriptions" + API)
            .then().statusCode(200);
    }

    @Test
    void aRefusedWriteDoesNotReachItsHandler() {
        String body = "{\"location\":\"eastus\"}";
        given().contentType("application/json").body(body).when().put(GROUP + API).then().statusCode(401);
        given().auth().oauth2(tokenFor("https://management.azure.com")).when().get(GROUP + API)
            .then().statusCode(404);
    }

    @Test
    void aValidTokenRunsTheWholeRequestIncludingItsInternalCalls() throws InterruptedException {
        String token = tokenFor("https://management.azure.com");
        String template = "{\"properties\":{\"mode\":\"Incremental\",\"template\":{"
            + "\"$schema\":\"https://schema.management.azure.com/schemas/2019-04-01/deploymentTemplate.json#\","
            + "\"contentVersion\":\"1.0.0.0\",\"resources\":[{"
            + "\"type\":\"Microsoft.ManagedIdentity/userAssignedIdentities\",\"apiVersion\":\"2023-01-31\","
            + "\"name\":\"strict-tokens-id\",\"location\":\"eastus\"}]}}}";
        String group = "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/strict-tokens-deploy-rg";
        String deployment = group + "/providers/Microsoft.Resources/deployments/strict-tokens";
        given().auth().oauth2(token).contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(group + API).then().statusCode(201);

        given().auth().oauth2(token).contentType("application/json").body(template)
            .when().put(deployment + API)
            .then().statusCode(201);
        assertEquals("Succeeded", awaitDeployment(token, deployment));

        // The deployment's resource goes through ArmRouter.dispatchInternal, so it exists only if that call passed.
        given().auth().oauth2(token)
            .when().get(group + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/strict-tokens-id"
                + "?api-version=2023-01-31")
            .then().statusCode(200);
    }

    @Test
    void aTokenFromAnotherTenantIsRefusedAndPointedAtTheSubscriptionsTenant() {
        given().auth().oauth2(tokenFor("https://management.azure.com"))
            .when().get("/subscriptions/" + OTHER_SUBSCRIPTION + "/resourcegroups" + API)
            .then().statusCode(401)
            .body("error.code", equalTo("InvalidAuthenticationTokenTenant"))
            .body("error.message", containsString("It must match the tenant '" + ownBaseUrl() + "/" + OTHER_TENANT))
            .header("WWW-Authenticate", startsWith("Bearer authorization_uri=\"" + ownBaseUrl() + "/" + OTHER_TENANT + "\""))
            .header("WWW-Authenticate", containsString("error=\"invalid_token\""));
    }

    @Test
    void theSubscriptionsOwnTenantIsServedAndTheDefaultSubscriptionRefusesIt() {
        String token = tokenFor(OTHER_TENANT, "https://management.azure.com");
        given().auth().oauth2(token).when().get("/subscriptions/" + OTHER_SUBSCRIPTION + "/resourcegroups" + API)
            .then().statusCode(200);
        given().auth().oauth2(token).when().get("/subscriptions/" + SUBSCRIPTION + "/resourcegroups" + API)
            .then().statusCode(401).body("error.code", equalTo("InvalidAuthenticationTokenTenant"));
    }

    @Test
    void aRequestNamingNoSubscriptionHasNoTenantToMatch() {
        given().auth().oauth2(tokenFor(OTHER_TENANT, "https://management.azure.com"))
            .when().get("/subscriptions" + API).then().statusCode(200);
    }

    @Test
    void aFirstUseBelongsToTheCallersTenantAndAnUnknownTenantRegistersNothing() {
        String fresh = "/subscriptions/77777777-2222-0000-0000-000000000000/resourcegroups" + API;
        String armToken = tokenFor("99999999-9999-0000-0000-000000000000", "https://management.azure.com");
        given().auth().oauth2(armToken).when().get(fresh)
            .then().statusCode(401).body("error.code", equalTo("InvalidAuthenticationTokenTenant"));

        String mine = tokenFor(OTHER_TENANT, "https://management.azure.com");
        given().auth().oauth2(mine).when().get(fresh).then().statusCode(200);
        // Registered under the first caller's tenant, not the default one the refused request resolved to.
        given().auth().oauth2(tokenFor("https://management.azure.com")).when().get(fresh)
            .then().statusCode(401).body("error.code", equalTo("InvalidAuthenticationTokenTenant"));
    }

    @Test
    void aTokenFromACommonAuthorityMatchesTheDefaultSubscription() {
        given().auth().oauth2(tokenFor("common", "https://management.azure.com"))
            .when().get("/subscriptions/" + SUBSCRIPTION + "/resourcegroups" + API)
            .then().statusCode(200);
    }

    @Test
    void theEmulatorsOwnEndpointsNeedNoToken() {
        given().when().get("/" + TENANT + "/discovery/v2.0/keys").then().statusCode(200);
        given().when().get("/metadata/endpoints?api-version=2022-09-01").then().statusCode(200);
    }
}
