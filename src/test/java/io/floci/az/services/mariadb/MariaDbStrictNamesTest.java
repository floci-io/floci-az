package io.floci.az.services.mariadb;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(MariaDbStrictNamesTest.StrictNames.class)
@DisplayName("MariaDB: parity-overrides.strict-names refuses a server name outside Azure's rule")
class MariaDbStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true", "floci-az.services.maria-db.mocked", "true");
        }
    }

    private static final String BASE =
        "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.DBforMariaDB/servers/";
    private static final String API = "?api-version=2018-06-01";
    private static final String BODY = "{\"location\":\"eastus\","
        + "\"properties\":{\"administratorLogin\":\"a\",\"administratorLoginPassword\":\"P@ssw0rd1234\",\"version\":\"10.3\"}}";

    @Test
    void aNameOutsideTheRuleIsRefusedAndAValidOneIsCreated() {
        for (String bad : new String[] {"md", "-md-server", "md-server-", "Md-Server", "md_server"}) {
            given().contentType("application/json").body(BODY).when().put(BASE + bad + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        }
        given().contentType("application/json").body(BODY).when().put(BASE + "md--server-1" + API)
            .then().statusCode(201);
    }
}
