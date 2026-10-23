package io.floci.az.services.email;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(EmailStrictNamesTest.StrictNames.class)
@DisplayName("Email: parity-overrides.strict-names refuses Communication names outside the spec's rules")
class EmailStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true");
        }
    }

    private static final String BASE = "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.Communication/";
    private static final String API = "?api-version=2023-03-31";
    private static final String BODY = "{\"location\":\"global\",\"properties\":{\"dataLocation\":\"United States\"}}";

    private static void assertRefused(String path) {
        given().contentType("application/json").body(BODY).when().put(BASE + path + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
    }

    private static void assertCreated(String path) {
        given().contentType("application/json").body(BODY).when().put(BASE + path + API)
            .then().statusCode(201);
    }

    @Test
    void aCommunicationServiceNameOutsideTheRuleIsRefused() {
        for (String bad : new String[] {"has.dot", "x".repeat(64)}) {
            assertRefused("communicationServices/" + bad);
        }
        assertCreated("communicationServices/good_comm-1");
    }

    @Test
    void anEmailServiceNameOutsideTheRuleIsRefused() {
        for (String bad : new String[] {"under_score", "has.dot", "x".repeat(64)}) {
            assertRefused("emailServices/" + bad);
        }
        assertCreated("emailServices/good-mail-1");
    }

    @Test
    void aDomainNameOutsideTheRuleIsRefused() {
        assertCreated("emailServices/strict-mail");
        assertRefused("emailServices/strict-mail/domains/" + "d".repeat(254));
        assertCreated("emailServices/strict-mail/domains/mail.example.com");
    }
}
