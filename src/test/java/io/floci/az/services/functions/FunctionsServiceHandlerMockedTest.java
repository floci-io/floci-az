package io.floci.az.services.functions;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link FunctionsServiceHandler} in {@code mocked=true} mode: the management plane
 * (create app, deploy/list function) works from state with no runtime container, and invocations
 * return a synthetic 200 stub instead of executing user code.
 */
@QuarkusTest
@TestProfile(FunctionsServiceHandlerMockedTest.MockedProfile.class)
@DisplayName("FunctionsServiceHandler — mocked mode (no Docker)")
@SuppressWarnings("unused")
class FunctionsServiceHandlerMockedTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.functions.mocked", "true");
        }
    }

    private static final String FN = "/devstoreaccount1-functions";

    @Test
    @DisplayName("deploy + list work, and invocation returns a synthetic 200 stub")
    void managementWorksAndInvokeIsStubbed() {
        // create app
        given()
            .contentType("application/json")
            .body("{\"runtime\":\"python\",\"linuxFxVersion\":\"Python|3.12\"}")
            .when().put(FN + "/admin/apps/mockapp")
            .then().statusCode(201);

        // deploy a function (no code — management plane only)
        given()
            .contentType("application/json")
            .body("{\"handler\":\"index.handler\"}")
            .when().put(FN + "/admin/apps/mockapp/functions/hello")
            .then().statusCode(201);

        // it is listable
        given()
            .when().get(FN + "/admin/apps/mockapp/functions")
            .then().statusCode(200)
            .body("value", hasSize(1));

        // invocation returns the synthetic stub (no container launched)
        given()
            .contentType("application/json")
            .body("{}")
            .when().post(FN + "/api/mockapp/hello")
            .then().statusCode(200)
            .body("mocked", equalTo(true))
            .body("function", equalTo("hello"));
    }

    @Test
    @DisplayName("invoking an unknown function still returns 404 in mocked mode")
    void unknownFunctionStill404() {
        given()
            .contentType("application/json")
            .body("{}")
            .when().post(FN + "/api/ghostapp/ghostfn")
            .then().statusCode(404);
    }
}
