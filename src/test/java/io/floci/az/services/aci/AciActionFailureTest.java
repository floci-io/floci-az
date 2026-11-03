package io.floci.az.services.aci;

import io.floci.az.services.aci.AciModels.ContainerGroup;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Container-backed mode ({@code mocked=false}) when an action does not reach its expected state: the
 * POST itself must report it, because a poll cannot reach every client. The Java SDK treats
 * restart's initial 204 as finished without polling, and stop has no LRO at all.
 */
@QuarkusTest
@TestProfile(AciActionFailureTest.RealModeProfile.class)
@DisplayName("AciHandler: an action that fails is reported on the POST")
class AciActionFailureTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.aci.mocked", "false");
        }
    }

    private static final String API = "?api-version=2023-05-01";
    private static final String GROUP = "/subscriptions/test-sub-aci-fail/resourceGroups/test-rg-aci-fail"
            + "/providers/Microsoft.ContainerInstance/containerGroups/cg-fail";

    private static final String CREATE_BODY = """
            {
              "location": "eastus",
              "properties": {
                "osType": "Linux",
                "containers": [{
                  "name": "web",
                  "properties": {
                    "image": "hashicorp/http-echo:latest",
                    "resources": {"requests": {"cpu": 0.5, "memoryInGB": 0.5}}
                  }
                }]
              }
            }
            """;

    @InjectMock
    AciContainerGroupManager containerManager;

    @BeforeEach
    void createGroup() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(GROUP + API)
                .then().statusCode(anyOf(is(200), is(201)));
    }

    @Test
    void aFailedStartIsAConflictWithNoOperationUrl() {
        when(containerManager.startGroupContainers(any(ContainerGroup.class))).thenReturn(false);

        assertRefused("start");
    }

    @Test
    void aFailedRestartIsAConflictWithNoOperationUrl() {
        when(containerManager.restartGroupContainers(any(ContainerGroup.class))).thenReturn(false);

        assertRefused("restart");
    }

    @Test
    void aFailedStopIsAConflictWithNoOperationUrl() {
        when(containerManager.stopGroupContainers(any(ContainerGroup.class))).thenReturn(false);

        assertRefused("stop");
    }

    @Test
    void aStartThatReachesItsStateIsAcceptedAndItsOperationSucceeds() {
        when(containerManager.startGroupContainers(any(ContainerGroup.class))).thenReturn(true);

        String location = given().when().post(GROUP + "/start" + API)
                .then().statusCode(202)
                .extract().header("Location");

        given().when().get(location)
                .then().statusCode(200)
                .body("status", equalTo("Succeeded"));
    }

    @Test
    void aRestartThatReachesItsStateKeepsItsLocationHeader() {
        when(containerManager.restartGroupContainers(any(ContainerGroup.class))).thenReturn(true);

        given().when().post(GROUP + "/restart" + API)
                .then().statusCode(204)
                .header("Location", containsString("/operations/"));
    }

    @Test
    void aStopThatReachesItsStateStaysABare204() {
        when(containerManager.stopGroupContainers(any(ContainerGroup.class))).thenReturn(true);

        given().when().post(GROUP + "/stop" + API)
                .then().statusCode(204)
                .header("Location", nullValue());
    }

    private void assertRefused(String action) {
        given().when().post(GROUP + "/" + action + API)
                .then().statusCode(409)
                .header("Location", nullValue())
                .body("error.code", equalTo("ContainerGroupActionFailed"))
                .body("error.message", containsString("'" + action + "'"))
                .body("error.message", containsString("'cg-fail'"));
    }
}
