package io.floci.az.services.sql;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(SqlStrictNamesTest.StrictNames.class)
@DisplayName("SQL: parity-overrides.strict-names refuses server, database and firewall rule names outside Azure's rules")
class SqlStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true", "floci-az.services.sql.mocked", "true");
        }
    }

    private static final String BASE = "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.Sql/servers/";
    private static final String API = "?api-version=2021-11-01";
    private static final String BODY = "{\"location\":\"eastus\",\"properties\":{"
        + "\"administratorLogin\":\"sa\",\"administratorLoginPassword\":\"FlociAz_Strong123!\"}}";

    @Test
    void aServerNameOutsideTheRuleIsRefusedAndAValidOneIsCreated() {
        for (String bad : new String[] {"Upper-case", "-leading", "trailing-", "under_score", "x".repeat(64)}) {
            given().contentType("application/json").body(BODY).when().put(BASE + bad + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        }
        given().contentType("application/json").body(BODY).when().put(BASE + "strict-srv-1" + API)
            .then().statusCode(201);
    }

    @Test
    void databaseAndFirewallRuleNamesOutsideTheirRulesAreRefused() {
        given().contentType("application/json").body(BODY).when().put(BASE + "strict-children" + API)
            .then().statusCode(201);

        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(BASE + "strict-children/databases/ends-with-dot." + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(BASE + "strict-children/databases/Orders_DB-1" + API)
            .then().statusCode(201);

        String rule = "{\"properties\":{\"startIpAddress\":\"10.0.0.1\",\"endIpAddress\":\"10.0.0.2\"}}";
        given().contentType("application/json").body(rule)
            .when().put(BASE + "strict-children/firewallRules/semi;colon" + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        given().contentType("application/json").body(rule)
            .when().put(BASE + "strict-children/firewallRules/AllowAllWindowsAzureIps" + API)
            .then().statusCode(201);
    }
}
