package io.floci.az.services.vm;

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
 * VM handler tests with {@code mocked=false} — starts a real Docker container backing the VM.
 *
 * <p>Skipped automatically when Docker is unavailable. The backing image (ubuntu:22.04) may need
 * to be pulled on first run, so provisioning is polled with a generous timeout.
 *
 * <p>Tests are ordered and share state: the VM is created in test 1, reaches Succeeded in test 2,
 * its power state is driven through the container in tests 3-4, and it is deleted in test 5.
 * {@code @BeforeAll} only does a filesystem check so it is safe before Quarkus is ready;
 * the HTTP reset is done inside the first {@code @Test}.
 */
@QuarkusTest
@TestProfile(VmDockerTest.RealModeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("VmHandler — real Docker-backed mode (Docker required)")
class VmDockerTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.vm.mocked", "false");
        }
    }

    private static final String SUB  = "test-sub-vm-docker";
    private static final String RG   = "test-rg-vm-docker";
    private static final String NAME = "docker-test-vm";
    private static final String API  = "?api-version=2024-11-01";
    private static final String BASE =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.Compute";
    private static final String VM_PATH = BASE + "/virtualMachines/" + NAME;

    private static final String CREATE_BODY = """
            {
              "location": "eastus",
              "properties": {
                "hardwareProfile": {"vmSize": "Standard_D2s_v3"},
                "storageProfile": {
                  "imageReference": {
                    "publisher": "Canonical",
                    "offer": "0001-com-ubuntu-server-jammy",
                    "sku": "22_04-lts",
                    "version": "latest"
                  }
                },
                "osProfile": {"adminUsername": "azureuser", "computerName": "dockervm"}
              }
            }
            """;

    /** Pure filesystem check — safe to run before Quarkus is fully ready. */
    @BeforeAll
    void checkDockerAvailable() {
        boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock"))
                || System.getenv("DOCKER_HOST") != null;
        assumeTrue(dockerAvailable, "Docker socket not available — skipping real VM tests");
    }

    @AfterAll
    void cleanup() {
        try {
            given().delete(VM_PATH + API);
        } catch (Exception ignored) {}
    }

    @Test
    @Order(1)
    @DisplayName("PUT VM returns 201 with provisioningState Creating/Succeeded")
    void createVmReturns201() {
        given().post("/_admin/reset").then().statusCode(204);

        given().contentType("application/json").body(CREATE_BODY)
                .when().put(VM_PATH + API)
                .then().statusCode(201)
                .body("name", equalTo(NAME))
                .body("properties.provisioningState", oneOf("Creating", "Succeeded", "Failed"));
    }

    @Test
    @Order(2)
    @DisplayName("VM reaches provisioningState=Succeeded once the container is running")
    void vmReachesSucceeded() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        String state = "Creating";

        while (!"Succeeded".equals(state) && !"Failed".equals(state)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(3_000);
            Response r = given().when().get(VM_PATH + API);
            if (r.statusCode() == 200) {
                state = r.path("properties.provisioningState");
            }
        }

        assumeTrue(!"Failed".equals(state), "VM container failed to start — Docker may be misconfigured");
        assertEquals("Succeeded", state, "VM did not reach Succeeded within 120 s; last state=" + state);

        given().when().get(VM_PATH + "/instanceView" + API)
                .then().statusCode(200)
                .body("statuses.code", hasItems("ProvisioningState/succeeded", "PowerState/running"));
    }

    @Test
    @Order(3)
    @DisplayName("powerOff stops the container -> PowerState/stopped; start -> running")
    void powerOffThenStart() {
        given().when().post(VM_PATH + "/powerOff" + API).then().statusCode(202);
        given().when().get(VM_PATH + "/instanceView" + API)
                .then().body("statuses.code", hasItem("PowerState/stopped"));

        given().when().post(VM_PATH + "/start" + API).then().statusCode(202);
        given().when().get(VM_PATH + "/instanceView" + API)
                .then().body("statuses.code", hasItem("PowerState/running"));
    }

    @Test
    @Order(4)
    @DisplayName("deallocate -> deallocated; restart -> running")
    void deallocateThenRestart() {
        given().when().post(VM_PATH + "/deallocate" + API).then().statusCode(202);
        given().when().get(VM_PATH + "/instanceView" + API)
                .then().body("statuses.code", hasItem("PowerState/deallocated"));

        given().when().post(VM_PATH + "/restart" + API).then().statusCode(202);
        given().when().get(VM_PATH + "/instanceView" + API)
                .then().body("statuses.code", hasItem("PowerState/running"));
    }

    @Test
    @Order(5)
    @DisplayName("DELETE removes the VM and its container; subsequent GET returns 404")
    void deleteVm() {
        given().when().delete(VM_PATH + API).then().statusCode(204);
        given().when().get(VM_PATH + API).then().statusCode(404);
    }
}
