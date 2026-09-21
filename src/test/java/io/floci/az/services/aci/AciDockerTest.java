package io.floci.az.services.aci;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ACI handler tests with {@code mocked=false} — starts real Docker containers backing the group.
 *
 * <p>Skipped automatically when Docker is unavailable. Uses two tiny images
 * ({@code hashicorp/http-echo} and {@code busybox}) that may need pulling on first run, so
 * provisioning is polled with a generous timeout.
 *
 * <p>The group deliberately has two containers: the secondary busybox loops
 * {@code wget http://localhost:5678}, so its log output containing the echo text proves the
 * shared-netns pod semantics (localhost between group members) AND the real log plumbing in one
 * assertion. Tests are ordered and share state; the HTTP reset is done inside the first test.</p>
 */
@QuarkusTest
@TestProfile(AciDockerTest.RealModeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AciHandler — real Docker-backed mode (Docker required)")
class AciDockerTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.aci.mocked", "false");
        }
    }

    private static final String SUB  = "test-sub-aci-docker";
    private static final String RG   = "test-rg-aci-docker";
    private static final String NAME = "docker-test-group";
    private static final String API  = "?api-version=2023-05-01";
    private static final String GROUP_PATH =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG
                    + "/providers/Microsoft.ContainerInstance/containerGroups/" + NAME;

    private static final String CREATE_BODY = """
            {
              "location": "eastus",
              "properties": {
                "containers": [
                  {
                    "name": "web",
                    "properties": {
                      "image": "hashicorp/http-echo:latest",
                      "command": ["/http-echo", "-listen=:5678", "-text=hello-from-pod"],
                      "ports": [{"port": 5678}],
                      "resources": {"requests": {"cpu": 0.5, "memoryInGB": 0.25}}
                    }
                  },
                  {
                    "name": "sidecar",
                    "properties": {
                      "image": "busybox:stable",
                      "command": ["sh", "-c", "while true; do wget -qO- http://localhost:5678 || true; sleep 1; done"],
                      "resources": {"requests": {"cpu": 0.25, "memoryInGB": 0.125}}
                    }
                  }
                ],
                "osType": "Linux",
                "ipAddress": {"type": "Public", "ports": [{"port": 5678}]}
              }
            }
            """;

    /** Pure filesystem check — safe to run before Quarkus is fully ready. */
    @BeforeAll
    void checkDockerAvailable() {
        boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock"))
                || System.getenv("DOCKER_HOST") != null;
        assumeTrue(dockerAvailable, "Docker socket not available — skipping real ACI tests");
    }

    @AfterAll
    void cleanup() {
        try {
            given().delete(GROUP_PATH + API);
        } catch (Exception ignored) {}
    }

    @Test
    @Order(1)
    @DisplayName("PUT group returns 201 with provisioningState Creating/Succeeded")
    void createGroupReturns201() {
        given().post("/_admin/reset").then().statusCode(204);

        given().contentType("application/json").body(CREATE_BODY)
                .when().put(GROUP_PATH + API)
                .then().statusCode(201)
                .body("name", equalTo(NAME))
                .body("properties.provisioningState", oneOf("Creating", "Succeeded", "Failed"));
    }

    @Test
    @Order(2)
    @DisplayName("Group reaches Succeeded once all containers run; instanceView is real")
    void groupReachesSucceeded() throws InterruptedException {
        String state = pollProvisioningState(180_000);

        // Deliberately not an assumption: a group that reaches Failed is the defect this test exists
        // to catch, and excusing it would make the one proof of the Docker path self-cancelling.
        assertEquals("Succeeded", state, "group did not reach Succeeded; last state=" + state);

        given().when().get(GROUP_PATH + API)
                .then().statusCode(200)
                .body("properties.instanceView.state", equalTo("Running"))
                .body("properties.containers[0].properties.instanceView.currentState.state", equalTo("Running"))
                .body("properties.containers[1].properties.instanceView.currentState.state", equalTo("Running"))
                .body("properties.ipAddress.ip", not(emptyOrNullString()));
    }

    @Test
    @Order(3)
    @DisplayName("Sidecar reaches the primary on localhost — shared netns — and logs prove it")
    void sidecarLogsShowSharedLocalhost() throws InterruptedException {
        // The sidecar polls localhost:5678 once a second; give it a few cycles.
        long deadline = System.currentTimeMillis() + 30_000;
        String content = "";
        while (!content.contains("hello-from-pod") && System.currentTimeMillis() < deadline) {
            Thread.sleep(2_000);
            Response r = given().when().get(GROUP_PATH + "/containers/sidecar/logs" + API);
            if (r.statusCode() == 200) {
                content = r.path("content");
            }
        }
        assertEquals(true, content.contains("hello-from-pod"),
                "sidecar logs never showed the primary's echo text; shared netns not working. Logs: "
                        + content.substring(0, Math.min(content.length(), 500)));
    }

    @Test
    @Order(4)
    @DisplayName("logs honor the tail parameter")
    void logsHonorTail() {
        String tailed = given().when()
                .get(GROUP_PATH + "/containers/sidecar/logs" + API + "&tail=1")
                .then().statusCode(200)
                .extract().path("content");
        assertEquals(true, tailed.strip().lines().count() <= 1,
                "tail=1 returned more than one line: " + tailed);
    }

    @Test
    @Order(5)
    @DisplayName("stop halts the containers; start brings them back")
    void stopThenStart() throws InterruptedException {
        given().when().post(GROUP_PATH + "/stop" + API).then().statusCode(204);
        Thread.sleep(2_000);
        given().when().get(GROUP_PATH + API)
                .then().statusCode(200)
                .body("properties.instanceView.state", equalTo("Stopped"));

        given().when().post(GROUP_PATH + "/start" + API).then().statusCode(202);
        long deadline = System.currentTimeMillis() + 60_000;
        String state = "Stopped";
        while (!"Running".equals(state) && System.currentTimeMillis() < deadline) {
            Thread.sleep(2_000);
            state = given().when().get(GROUP_PATH + API).path("properties.instanceView.state");
        }
        assertEquals("Running", state, "group did not return to Running after start");
    }

    @Test
    @Order(6)
    @DisplayName("DELETE removes the group and its containers; subsequent GET returns 404")
    void deleteGroup() {
        // 200 with the deleted group's body when it existed, 404 afterwards, 204 once absent.
        given().when().delete(GROUP_PATH + API).then().statusCode(200).body("name", equalTo(NAME));
        given().when().get(GROUP_PATH + API).then().statusCode(404);
        given().when().delete(GROUP_PATH + API).then().statusCode(204);
    }

    private String pollProvisioningState(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String state = "Creating";
        while (!"Succeeded".equals(state) && !"Failed".equals(state)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(3_000);
            Response r = given().when().get(GROUP_PATH + API);
            if (r.statusCode() == 200) {
                state = r.path("properties.provisioningState");
            }
        }
        return state;
    }
}
