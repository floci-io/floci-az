package io.floci.az.services.vm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Quarkus-level tests for {@link VmHandler}, exercising the Microsoft.Compute/virtualMachines
 * ARM surface in mocked mode (no Docker).
 */
@QuarkusTest
@TestProfile(VmHandlerTest.MockedProfile.class)
@DisplayName("VmHandler — lifecycle and power state (mocked mode)")
@SuppressWarnings("unused")
class VmHandlerTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.vm.mocked", "true");
        }
    }

    private static final String SUB  = "test-sub-vm";
    private static final String RG   = "test-rg-vm";
    private static final String API  = "?api-version=2024-11-01";
    private static final String BASE =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.Compute";

    private static final String CREATE_BODY = """
            {
              "location": "eastus",
              "tags": {"env": "test"},
              "properties": {
                "hardwareProfile": {"vmSize": "Standard_D2s_v3"},
                "storageProfile": {
                  "imageReference": {
                    "publisher": "Canonical",
                    "offer": "0001-com-ubuntu-server-jammy",
                    "sku": "22_04-lts",
                    "version": "latest"
                  },
                  "osDisk": {"createOption": "FromImage", "name": "myOsDisk"}
                },
                "osProfile": {"adminUsername": "azureuser", "computerName": "myvm"},
                "networkProfile": {
                  "networkInterfaces": [
                    {"id": "/subscriptions/test-sub-vm/resourceGroups/test-rg-vm/providers/Microsoft.Network/networkInterfaces/myvm-nic"}
                  ]
                }
              }
            }
            """;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private void createVm(String vmName) {
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(BASE + "/virtualMachines/" + vmName + API)
                .then().statusCode(201);
    }

    @Test
    @DisplayName("GET unknown VM returns 404 ResourceNotFound")
    void getUnknownVmReturns404() {
        given().when().get(BASE + "/virtualMachines/no-such-vm" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    @DisplayName("PUT creates VM (201) with Succeeded state and echoed properties")
    void createVmReturns201() {
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(BASE + "/virtualMachines/vm1" + API)
                .then().statusCode(201)
                .body("name", equalTo("vm1"))
                .body("type", equalTo("Microsoft.Compute/virtualMachines"))
                .body("location", equalTo("eastus"))
                .body("tags.env", equalTo("test"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.vmId", not(emptyOrNullString()))
                .body("properties.hardwareProfile.vmSize", equalTo("Standard_D2s_v3"))
                .body("properties.osProfile.adminUsername", equalTo("azureuser"));
    }

    @Test
    @DisplayName("PUT existing VM returns 200 (update)")
    void updateVmReturns200() {
        createVm("vm1");
        given().contentType("application/json").body(CREATE_BODY)
                .when().put(BASE + "/virtualMachines/vm1" + API)
                .then().statusCode(200);
    }

    @Test
    @DisplayName("GET VM with $expand=instanceView includes power state running")
    void getWithInstanceViewExpand() {
        createVm("vm1");
        given().when().get(BASE + "/virtualMachines/vm1?api-version=2024-11-01&$expand=instanceView")
                .then().statusCode(200)
                .body("properties.instanceView.statuses.code", hasItem("PowerState/running"));
    }

    @Test
    @DisplayName("GET instanceView returns ProvisioningState and PowerState statuses")
    void instanceViewStatuses() {
        createVm("vm1");
        given().when().get(BASE + "/virtualMachines/vm1/instanceView" + API)
                .then().statusCode(200)
                .body("computerName", equalTo("myvm"))
                .body("statuses.code", hasItems("ProvisioningState/succeeded", "PowerState/running"));
    }

    @Test
    @DisplayName("powerOff -> stopped, start -> running, deallocate -> deallocated")
    void powerTransitions() {
        createVm("vm1");

        given().when().post(BASE + "/virtualMachines/vm1/powerOff" + API).then().statusCode(202);
        given().when().get(BASE + "/virtualMachines/vm1/instanceView" + API)
                .then().body("statuses.code", hasItem("PowerState/stopped"));

        given().when().post(BASE + "/virtualMachines/vm1/start" + API).then().statusCode(202);
        given().when().get(BASE + "/virtualMachines/vm1/instanceView" + API)
                .then().body("statuses.code", hasItem("PowerState/running"));

        given().when().post(BASE + "/virtualMachines/vm1/deallocate" + API).then().statusCode(202);
        given().when().get(BASE + "/virtualMachines/vm1/instanceView" + API)
                .then().body("statuses.code", hasItem("PowerState/deallocated"));
    }

    @Test
    @DisplayName("power action returns Azure-AsyncOperation header for SDK LRO polling")
    void powerActionEmitsAsyncHeader() {
        createVm("vm1");
        given().when().post(BASE + "/virtualMachines/vm1/restart" + API)
                .then().statusCode(202)
                .header("Azure-AsyncOperation", containsString("/operations/"));
    }

    @Test
    @DisplayName("List by resource group returns created VMs")
    void listByResourceGroup() {
        createVm("vm1");
        createVm("vm2");
        given().when().get(BASE + "/virtualMachines" + API)
                .then().statusCode(200)
                .body("value.name", hasItems("vm1", "vm2"));
    }

    @Test
    @DisplayName("DELETE removes the VM (then GET 404)")
    void deleteVm() {
        createVm("vm1");
        given().when().delete(BASE + "/virtualMachines/vm1" + API).then().statusCode(204);
        given().when().get(BASE + "/virtualMachines/vm1" + API).then().statusCode(404);
        // Deleting a non-existent VM is idempotent (204).
        given().when().delete(BASE + "/virtualMachines/vm1" + API).then().statusCode(204);
    }

    @Test
    @DisplayName("Network interface stub synthesizes a private IP and Succeeded state")
    void networkInterfaceStub() {
        String nicPath = "/subscriptions/" + SUB + "/resourceGroups/" + RG
                + "/providers/Microsoft.Network/networkInterfaces/myvm-nic";
        given().contentType("application/json")
                .body("""
                    {"location":"eastus","properties":{"ipConfigurations":[
                      {"name":"ipconfig1","properties":{"subnet":{"id":"/subscriptions/x/.../subnets/default"}}}
                    ]}}
                    """)
                .when().put(nicPath + API)
                .then().statusCode(200)
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.ipConfigurations[0].properties.privateIPAddress", not(emptyOrNullString()));
    }
}
