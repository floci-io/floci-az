package io.floci.az.services.aci;

import io.floci.az.testing.StrictNamesProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(StrictNamesProfile.class)
@DisplayName("ACI: parity-overrides.strict-names refuses a container group name outside Azure's rule")
class AciStrictNamesTest {

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
