package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;

/**
 * The two generic ARM resource listings — {@code /subscriptions/{sub}/resources} and
 * {@code .../resourceGroups/{rg}/resources} — across an estate that spans two resource groups
 * and mixes a provider ArmHandler holds itself (Storage) with one that registers through
 * {@code ResourceIndexContributor} (Compute).
 *
 * <p>Per-service tests can only show that a service reaches the index. Only a test that owns the
 * whole estate can show the scoping: that the resource-group listing is confined to its group
 * while the subscription listing spans every group.</p>
 */
@QuarkusTest
@TestProfile(ArmResourceIndexTest.MockedProfile.class)
@DisplayName("ARM resource index — resource-group and subscription scope")
class ArmResourceIndexTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.vm.mocked", "true");
        }
    }

    private static final String SUB = "test-sub-index";
    private static final String RG_A = "test-rg-index-a";
    private static final String RG_B = "test-rg-index-b";

    private static final String VM_BODY = """
            {
              "location": "eastus",
              "properties": {
                "hardwareProfile": {"vmSize": "Standard_D2s_v3"},
                "storageProfile": {
                  "imageReference": {"publisher": "Canonical", "offer": "0001-com-ubuntu-server-jammy",
                                     "sku": "22_04-lts", "version": "latest"},
                  "osDisk": {"createOption": "FromImage", "name": "osdisk"}
                },
                "osProfile": {"adminUsername": "azureuser", "computerName": "indexvm"}
              }
            }
            """;

    @BeforeEach
    void seedEstate() {
        given().post("/_admin/reset").then().statusCode(204);
        createGroup(RG_A);
        createGroup(RG_B);
        createVm(RG_A, "vm-a");
        createVm(RG_B, "vm-b");
        createStorageAccount(RG_A, "indexsaa");
        createKeyVault(RG_A, "indexkv");
    }

    private void createGroup(String rg) {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .when().put("/subscriptions/" + SUB + "/resourceGroups/" + rg + "?api-version=2021-04-01")
                .then().statusCode(oneOf(200, 201));
    }

    private void createVm(String rg, String name) {
        given().contentType("application/json").body(VM_BODY)
                .when().put("/subscriptions/" + SUB + "/resourceGroups/" + rg
                        + "/providers/Microsoft.Compute/virtualMachines/" + name + "?api-version=2024-11-01")
                .then().statusCode(201);
    }

    private void createKeyVault(String rg, String name) {
        given().contentType("application/json")
                .body("{\"location\":\"eastus\",\"properties\":{\"tenantId\":\"t\","
                        + "\"sku\":{\"family\":\"A\",\"name\":\"standard\"}}}")
                .when().put("/subscriptions/" + SUB + "/resourceGroups/" + rg
                        + "/providers/Microsoft.KeyVault/vaults/" + name + "?api-version=2023-07-01")
                .then().statusCode(oneOf(200, 201));
    }

    private void createStorageAccount(String rg, String name) {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .when().put("/subscriptions/" + SUB + "/resourceGroups/" + rg
                        + "/providers/Microsoft.Storage/storageAccounts/" + name + "?api-version=2023-01-01")
                .then().statusCode(oneOf(200, 201));
    }

    @Test
    @DisplayName("A resource-group listing carries that group's resources and no other group's")
    void resourceGroupListingIsScopedToItsGroup() {
        given().when().get("/subscriptions/" + SUB + "/resourceGroups/" + RG_A + "/resources?api-version=2021-04-01")
                .then().statusCode(200)
                .body("value.name", containsInAnyOrder("indexsaa", "indexkv", "vm-a"));

        given().when().get("/subscriptions/" + SUB + "/resourceGroups/" + RG_B + "/resources?api-version=2021-04-01")
                .then().statusCode(200)
                .body("value.name", contains("vm-b"));
    }

    @Test
    @DisplayName("The subscription listing spans every resource group")
    void subscriptionListingSpansGroups() {
        given().when().get("/subscriptions/" + SUB + "/resources?api-version=2021-04-01")
                .then().statusCode(200)
                .body("value.name", containsInAnyOrder("indexsaa", "indexkv", "vm-a", "vm-b"))
                .body("value.findAll { it.type == 'Microsoft.Compute/virtualMachines' }", hasSize(2));
    }

    @Test
    @DisplayName("A contributor's index entry carries identity only, without properties")
    void contributorEntriesCarryIdentityOnly() {
        given().when().get("/subscriptions/" + SUB + "/resources?api-version=2021-04-01")
                .then().statusCode(200)
                .body("value.find { it.name == 'vm-a' }.type", equalTo("Microsoft.Compute/virtualMachines"))
                .body("value.find { it.name == 'vm-a' }.location", equalTo("eastus"))
                .body("value.find { it.name == 'vm-a' }.id",
                        equalTo("/subscriptions/" + SUB + "/resourceGroups/" + RG_A
                                + "/providers/Microsoft.Compute/virtualMachines/vm-a"))
                .body("value.find { it.name == 'vm-a' }.properties", equalTo(null));
    }

    @Test
    @DisplayName("Key vaults keep their properties so azurerm can look one up by vaultUri")
    void keyVaultEntriesKeepVaultUri() {
        given().when().get("/subscriptions/" + SUB + "/resources?api-version=2021-04-01")
                .then().statusCode(200)
                .body("value.find { it.name == 'indexkv' }.properties.vaultUri", not(emptyOrNullString()));
    }
}
