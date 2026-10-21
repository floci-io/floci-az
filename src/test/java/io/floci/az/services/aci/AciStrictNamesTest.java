package io.floci.az.services.aci;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(AciStrictNamesTest.StrictNames.class)
@DisplayName("ACI: parity-overrides.strict-names refuses a container group name outside Azure's rule")
class AciStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true", "floci-az.services.aci.mocked", "true");
        }
    }

    private static final String BASE =
        "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.ContainerInstance/containerGroups/";
    private static final String API = "?api-version=2023-05-01";
    private static final String BODY = "{\"location\":\"eastus\",\"properties\":{\"osType\":\"Linux\",\"containers\":[{\"name\":\"web\",\"properties\":{\"image\":\"nginx:1.27\",\"resources\":{\"requests\":{\"cpu\":1,\"memoryInGB\":1}}}}]}}";

    @Test
    void aNameOutsideTheRuleIsRefusedAndAValidOneIsCreated() {
        for (String bad : new String[] {"Upper", "two--hyphens", "-lead"}) {
            given().contentType("application/json").body(BODY).when().put(BASE + bad + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        }
        given().contentType("application/json").body(BODY).when().put(BASE + "good-group-1" + API)
            .then().statusCode(201);
    }
}
