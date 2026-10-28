package io.floci.az.services.managedidentity;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestProfile(UserAssignedIdentityRuleTest.MockedAcr.class)
class UserAssignedIdentityRuleTest {

    /** The rule reads registry records; it needs no registry:2 container. */
    public static class MockedAcr implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.acr.mocked", "true");
        }
    }

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
            .body("error.code", equalTo("FailedIdentityOperation"))
            .body("error.message", containsString("Resource '" + IDENTITIES + "nobody' was not found."));

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

    @Test
    void aPatchNamingAMissingIdentityIsRefusedAndChangesNothing() {
        given().contentType("application/json").body("{\"location\":\"westeurope\"}")
            .when().put(RG + "?api-version=2021-04-01")
            .then().statusCode(anyOf(is(200), is(201)));
        String registry = RG + "/providers/Microsoft.ContainerRegistry/registries/uairulepatch?api-version=2023-07-01";
        given().contentType("application/json").body("{\"location\":\"westeurope\",\"tags\":{\"env\":\"dev\"},\"properties\":{}}")
            .when().put(registry)
            .then().statusCode(anyOf(is(200), is(201)));

        given().contentType("application/json")
            .body("{\"tags\":{\"env\":\"prod\"},\"identity\":{\"type\":\"UserAssigned\",\"userAssignedIdentities\":{\""
                + IDENTITIES + "nobody\":{}}}}")
            .when().patch(registry)
            .then().statusCode(400)
            .body("error.code", equalTo("FailedIdentityOperation"));

        given().when().get(registry).then().statusCode(200)
            .body("tags.env", equalTo("dev"))
            .body("identity", nullValue());
    }

    @Test
    void aPatchRemovingADeletedIdentityPasses() {
        given().contentType("application/json").body("{\"location\":\"westeurope\"}")
            .when().put(RG + "?api-version=2021-04-01")
            .then().statusCode(anyOf(is(200), is(201)));
        String identity = IDENTITIES + "gone";
        given().contentType("application/json").body("{\"location\":\"westeurope\"}")
            .when().put(identity + "?api-version=2023-01-31")
            .then().statusCode(anyOf(is(200), is(201)));
        String registry = RG + "/providers/Microsoft.ContainerRegistry/registries/uairuleremove?api-version=2023-07-01";
        given().contentType("application/json").body(appBody(identity))
            .when().put(registry)
            .then().statusCode(anyOf(is(200), is(201)));
        given().when().delete(identity + "?api-version=2023-01-31").then().statusCode(anyOf(is(200), is(204)));

        given().contentType("application/json")
            .body("{\"identity\":{\"type\":\"UserAssigned\",\"userAssignedIdentities\":{\"" + identity + "\":null}}}")
            .when().patch(registry)
            .then().statusCode(200);
    }
}
