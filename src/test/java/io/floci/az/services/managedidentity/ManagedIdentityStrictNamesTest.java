package io.floci.az.services.managedidentity;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(ManagedIdentityStrictNamesTest.StrictNames.class)
@DisplayName("Managed Identity: parity-overrides.strict-names refuses identity and credential names outside Azure's rule")
class ManagedIdentityStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true");
        }
    }

    private static final String BASE =
        "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.ManagedIdentity/userAssignedIdentities/";
    private static final String API = "?api-version=2024-11-30";
    private static final String BODY = "{\"location\":\"eastus\"}";
    private static final String FIC_BODY =
        "{\"properties\":{\"issuer\":\"https://issuer\",\"subject\":\"s\",\"audiences\":[\"a\"]}}";

    @Test
    void anIdentityNameOutsideTheRuleIsRefusedBeforeTheHandler() {
        for (String bad : new String[] {"ab", "-identity", "_identity", "id.dot", "x".repeat(129)}) {
            given().contentType("application/json").body(BODY).when().put(BASE + bad + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        }
        given().contentType("application/json").body(BODY).when().put(BASE + "Good_Identity-1" + API)
            .then().body("error.code", not(equalTo("InvalidResourceName")));
    }

    @Test
    void aCredentialNameOutsideTheRuleIsRefusedBeforeTheHandler() {
        given().contentType("application/json").body(BODY).when().put(BASE + "fic-parent" + API)
            .then().statusCode(201);
        for (String bad : new String[] {"ab", "-fic", "fic.dot", "x".repeat(121)}) {
            given().contentType("application/json").body(FIC_BODY)
                .when().put(BASE + "fic-parent/federatedIdentityCredentials/" + bad + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        }
        given().contentType("application/json").body(FIC_BODY)
            .when().put(BASE + "fic-parent/federatedIdentityCredentials/" + "x".repeat(120) + API)
            .then().statusCode(201);
    }
}
