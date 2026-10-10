package io.floci.az.services.vm;

import io.floci.az.services.vm.VmModels.VirtualMachine;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * Container-backed mode ({@code mocked=false}) when the backing container cannot be started:
 * the VM must report {@code provisioningState=Failed}, not a running VM that does not exist.
 */
@QuarkusTest
@TestProfile(VmHandlerContainerStartFailureTest.RealModeProfile.class)
@DisplayName("VmHandler: container start failure in container-backed mode")
class VmHandlerContainerStartFailureTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.vm.mocked", "false");
        }
    }

    private static final String API = "?api-version=2024-11-01";
    private static final String VM_PATH = "/subscriptions/test-sub-vm-fail/resourceGroups/test-rg-vm-fail"
            + "/providers/Microsoft.Compute/virtualMachines/broken-vm";

    private static final String CREATE_BODY = """
            {
              "location": "eastus",
              "properties": {
                "hardwareProfile": {"vmSize": "Standard_D2s_v3"},
                "osProfile": {"adminUsername": "azureuser", "computerName": "broken-vm"}
              }
            }
            """;

    @InjectMock
    VmContainerManager containerManager;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        doThrow(new IllegalStateException("image pull failed"))
                .when(containerManager).startVm(any(VirtualMachine.class));
    }

    @Test
    void createWhoseContainerCannotStartReportsFailed() {
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(VM_PATH + API)
                .then().statusCode(201)
                .body("properties.provisioningState", equalTo("Failed"));

        given().when().get(VM_PATH + API)
                .then().statusCode(200)
                .body("properties.provisioningState", equalTo("Failed"));
    }

    @Test
    void failedVmInstanceViewReportsProvisioningFailedAndNoPowerState() {
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(VM_PATH + API)
                .then().statusCode(201);

        given().when().get(VM_PATH + "/instanceView" + API)
                .then().statusCode(200)
                .body("statuses.code", hasItem("ProvisioningState/failed"))
                .body("statuses.find { it.code == 'ProvisioningState/failed' }.level", equalTo("Error"))
                .body("statuses.code", not(hasItem(startsWith("PowerState/"))));

        given().queryParam("$expand", "instanceView")
                .when().get(VM_PATH + API)
                .then().statusCode(200)
                .body("properties.instanceView.statuses.code", not(hasItem(startsWith("PowerState/"))));
    }

    @Test
    void putOnFailedVmRetriesProvisioning() {
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(VM_PATH + API)
                .then().statusCode(201)
                .body("properties.provisioningState", equalTo("Failed"));

        doNothing().when(containerManager).startVm(any(VirtualMachine.class));

        given().contentType("application/json").body(CREATE_BODY)
                .when().put(VM_PATH + API)
                .then().statusCode(200)
                .body("properties.provisioningState", equalTo("Creating"));
    }

    @Test
    void startOnFailedVmThatStillCannotStartStaysFailed() {
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(VM_PATH + API)
                .then().statusCode(201);

        given().when().post(VM_PATH + "/start" + API)
                .then().statusCode(202);

        given().when().get(VM_PATH + "/instanceView" + API)
                .then().statusCode(200)
                .body("statuses.code", hasItem("ProvisioningState/failed"))
                .body("statuses.code", not(hasItem(startsWith("PowerState/"))));
    }

    @Test
    void startOnFailedVmRetriesProvisioning() {
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(VM_PATH + API)
                .then().statusCode(201);

        doNothing().when(containerManager).startVm(any(VirtualMachine.class));

        given().when().post(VM_PATH + "/start" + API)
                .then().statusCode(202);

        given().when().get(VM_PATH + API)
                .then().statusCode(200)
                .body("properties.provisioningState", equalTo("Creating"));
    }

    @Test
    void overlappingStartsOnFailedVmProvisionOnlyOnce() throws Exception {
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(VM_PATH + API)
                .then().statusCode(201);

        AtomicInteger provisions = new AtomicInteger();
        doAnswer(invocation -> {
            provisions.incrementAndGet();
            Thread.sleep(500);
            return null;
        }).when(containerManager).startVm(any(VirtualMachine.class));

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(
                () -> given().when().post(VM_PATH + "/start" + API).statusCode());
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(
                () -> given().when().post(VM_PATH + "/start" + API).statusCode());

        assertThat(first.get(30, TimeUnit.SECONDS), equalTo(202));
        assertThat(second.get(30, TimeUnit.SECONDS), equalTo(202));
        assertThat(provisions.get(), equalTo(1));
    }

    @Test
    void powerOffOnFailedVmLeavesItFailedSoAPutStillRetries() {
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(VM_PATH + API)
                .then().statusCode(201);

        given().when().post(VM_PATH + "/powerOff" + API)
                .then().statusCode(202);

        given().when().get(VM_PATH + API)
                .then().statusCode(200)
                .body("properties.provisioningState", equalTo("Failed"));

        doNothing().when(containerManager).startVm(any(VirtualMachine.class));

        given().contentType("application/json").body(CREATE_BODY)
                .when().put(VM_PATH + API)
                .then().statusCode(200)
                .body("properties.provisioningState", equalTo("Creating"));
    }

    @Test
    void failedVmCanBeDeleted() {
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(VM_PATH + API)
                .then().statusCode(201);

        given().when().delete(VM_PATH + API)
                .then().statusCode(204);

        given().when().get(VM_PATH + API)
                .then().statusCode(404);
    }
}
