package io.floci.az.services.managedidentity;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class UserAssignedIdentityRuleTest {

    private static final String RG = "/subscriptions/sub-uairule/resourceGroups/rg-uairule";
    private static final String IDENTITIES = RG + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/";

    private static String appBody(String identityId) {
        return "{\"location\":\"westeurope\",\"identity\":{\"type\":\"UserAssigned\",\"userAssignedIdentities\":{\""
            + identityId + "\":{}}},\"properties\":{}}";
    }

    @Test
    void aWriteNamingAMissingIdentityIsRefusedAndNotStored() {
        String app = RG + "/providers/Microsoft.App/containerApps/app-missing?api-version=2024-03-01";
        given().contentType("application/json").body(appBody(IDENTITIES + "nobody"))
            .when().put(app)
            .then().statusCode(400)
            .body("error.code", equalTo("FailedIdentityOperation"));

        given().when().get(app).then().statusCode(404);
    }

    @Test
    void aWriteNamingAnExistingIdentityPassesTheRule() {
        given().contentType("application/json").body("{\"location\":\"westeurope\"}")
            .when().put(RG + "?api-version=2021-04-01")
            .then().statusCode(anyOf(is(200), is(201)));
        given().contentType("application/json").body("{\"location\":\"westeurope\"}")
            .when().put(IDENTITIES + "somebody?api-version=2023-01-31")
            .then().statusCode(anyOf(is(200), is(201)));

        given().contentType("application/json").body(appBody(IDENTITIES + "SomeBody"))
            .when().put(RG + "/providers/Microsoft.ContainerRegistry/registries/uairuleacr?api-version=2023-07-01")
            .then().statusCode(anyOf(is(200), is(201)));
    }
}
