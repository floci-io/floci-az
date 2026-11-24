package io.floci.az.services.vm;

import io.floci.az.testing.StrictNamesProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(StrictNamesProfile.class)
@DisplayName("VM: parity-overrides.strict-names refuses a virtual machine name outside Azure's rule")
class VmStrictNamesTest {

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
