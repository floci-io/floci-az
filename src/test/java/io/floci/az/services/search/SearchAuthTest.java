package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;

@QuarkusTest
@TestProfile(SearchAuthTest.StrictSearchKeys.class)
@DisplayName("Azure AI Search: parity-overrides.strict-search-keys checks the caller of the data plane")
class SearchAuthTest {

    public static class StrictSearchKeys implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-search-keys", "true");
        }
    }

    private static final String SERVICE = "strictkeys";
    private static final String ARM_SERVICE = "/subscriptions/5ea4c400-0000-4000-8000-000000000003/resourceGroups/rg"
            + "/providers/Microsoft.Search/searchServices/" + SERVICE;
    private static final String ARM_API = "?api-version=2025-05-01";
    private static final String INDEXES = "/" + SERVICE + "-search/indexes?api-version=2025-09-01";

    @BeforeEach
    void createTheService() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"location\": \"westus\", \"sku\": {\"name\": \"basic\"}}")
            .when().put(ARM_SERVICE + ARM_API).then().statusCode(201);
    }

    private static String adminKey(String which) {
        return given().when().post(ARM_SERVICE + "/listAdminKeys" + ARM_API).then().statusCode(200)
            .extract().path(which);
    }

    @Test
    void eitherAdminKeyMayManageIndexes() {
        given().header("api-key", adminKey("primaryKey")).when().get(INDEXES).then().statusCode(200);
        given().header("api-key", adminKey("secondaryKey")).when().get(INDEXES).then().statusCode(200);
    }

    @Test
    void aKeyThatWasRegeneratedNoLongerPasses() {
        String old = adminKey("primaryKey");
        given().when().post(ARM_SERVICE + "/regenerateAdminKey/primary" + ARM_API).then().statusCode(200);

        given().header("api-key", old).when().get(INDEXES).then().statusCode(403);
        given().header("api-key", adminKey("primaryKey")).when().get(INDEXES).then().statusCode(200);
    }

    @Test
    void aQueryKeyAndAnUnknownKeyAreForbiddenAndNoKeyIsChallenged() {
        String queryKey = given().when().post(ARM_SERVICE + "/listQueryKeys" + ARM_API).then().statusCode(200)
            .extract().path("value[0].key");

        given().header("api-key", queryKey).when().get(INDEXES).then().statusCode(403)
            .body("error.code", equalTo(""));
        given().header("api-key", "not-a-key").when().get(INDEXES).then().statusCode(403)
            .body("error.message", equalTo("The given API key doesn't match service's internal, primary or"
                    + " secondary keys."));
        // As on Azure: 401, no body, and a challenge naming the tenant's authority and the search audience.
        given().when().get(INDEXES).then().statusCode(401)
            .header("WWW-Authenticate", matchesPattern("Bearer authorization=\"http://[^\"]+/[0-9a-f-]+"
                    + "/oauth2/authorize\" resource=\"https://search\\.azure\\.com\""))
            .body(emptyString());
    }

    @Test
    void aBearerTokenIsRefusedByAServiceThatTakesApiKeysOnly() {
        // A new service stores authOptions {"apiKeyOnly": {}}: "only the API key can be used".
        given().when().get(ARM_SERVICE + ARM_API).then().statusCode(200)
            .body("properties.authOptions", hasKey("apiKeyOnly"));

        // Azure: 401 invalid_token with no body.
        given().header("Authorization", "Bearer any-token").when().get(INDEXES).then().statusCode(401)
            .header("WWW-Authenticate", equalTo("Bearer realm=\"Azure Cognitive Search\" error=\"invalid_token\""
                    + " error_description=\"Authentication token failed validation.\""))
            .body(emptyString());
        given().header("Authorization", "Bearer any-token").header("api-key", adminKey("primaryKey"))
            .when().get(INDEXES).then().statusCode(401);
    }

    @Test
    void aServiceThatTakesTokensOrKeysAcceptsEither() {
        given().contentType("application/json")
            .body("{\"properties\": {\"authOptions\": {\"aadOrApiKey\": {\"aadAuthFailureMode\": \"http403\"}}}}")
            .when().patch(ARM_SERVICE + ARM_API).then().statusCode(200);

        given().header("Authorization", "Bearer any-token").when().get(INDEXES).then().statusCode(200);
        given().header("api-key", adminKey("primaryKey")).when().get(INDEXES).then().statusCode(200);
        given().header("api-key", "not-a-key").when().get(INDEXES).then().statusCode(403);
    }

    @Test
    void aServiceThatDisablesLocalAuthRefusesItsAdminKeyButNotATokenUnderStrict() {
        String key = adminKey("primaryKey");
        given().contentType("application/json").body("{\"properties\": {\"disableLocalAuth\": true}}")
            .when().patch(ARM_SERVICE + ARM_API).then().statusCode(200);

        given().header("api-key", key).when().get(INDEXES).then().statusCode(403);
        given().header("Authorization", "Bearer any-token").when().get(INDEXES).then().statusCode(200);
    }

    @Test
    void aServiceArmNeverDeclaredIsNotServed() {
        given().header("api-key", adminKey("primaryKey"))
            .when().get("/undeclared-search/indexes?api-version=2025-09-01")
            .then().statusCode(403);
    }
}
