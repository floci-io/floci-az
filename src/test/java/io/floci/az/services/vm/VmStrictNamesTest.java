package io.floci.az.services.vm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(VmStrictNamesTest.StrictNames.class)
@DisplayName("VM: parity-overrides.strict-names refuses a virtual machine name outside Azure's rule")
class VmStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true", "floci-az.services.vm.mocked", "true");
        }
    }

    private static final String BASE =
        "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.Compute/virtualMachines/";
    private static final String API = "?api-version=2024-07-01";
    private static final String BODY =
        "{\"location\":\"eastus\",\"properties\":{\"hardwareProfile\":{\"vmSize\":\"Standard_B1s\"}}}";

    @Test
    void aNameOutsideTheRuleIsRefusedAndAValidOneIsCreated() {
        for (String bad : new String[] {"under_score", "ends-", "ends.", "semi;colon"}) {
            given().contentType("application/json").body(BODY).when().put(BASE + bad + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        }
        given().contentType("application/json").body(BODY).when().put(BASE + "Good-Vm.01" + API)
            .then().statusCode(201);
    }
}
