package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
@DisplayName("ARM Managed HSM")
class ArmHandlerTest {

    @Test
    @DisplayName("create, get by name, list by subscription/RG, and delete a Managed HSM")
    void managedHsmCreateGetListDelete() {
        String sub = "hsm-sub-1";
        String rg = "hsm-rg-1";
        String name = "hsm-1";
        String base = "/subscriptions/" + sub + "/resourceGroups/" + rg
                + "/providers/Microsoft.KeyVault/managedHSMs/" + name;

        given().contentType("application/json")
                .body("{\"location\":\"eastus\",\"sku\":{\"family\":\"B\",\"name\":\"Standard_B1\"},"
                        + "\"properties\":{\"initialAdminObjectIds\":[\"obj-1\"]}}")
                .when().put(base + "?api-version=2023-07-01")
                .then().statusCode(200)
                .body("name", equalTo(name))
                .body("type", equalTo("Microsoft.KeyVault/managedHSMs"))
                .body("properties.hsmUri", equalTo("https://" + name + ".managedhsm.azure.net/"))
                .body("sku.name", equalTo("Standard_B1"));

        given().when().get(base + "?api-version=2023-07-01")
                .then().statusCode(200)
                .body("name", equalTo(name));

        given().when().get("/subscriptions/" + sub + "/resourceGroups/" + rg
                        + "/providers/Microsoft.KeyVault/managedHSMs?api-version=2023-07-01")
                .then().statusCode(200)
                .body("value", hasItem(hasEntry("name", name)));

        given().when().get("/subscriptions/" + sub + "/providers/Microsoft.KeyVault/managedHSMs?api-version=2023-07-01")
                .then().statusCode(200)
                .body("value", hasItem(hasEntry("name", name)));

        given().when().delete(base + "?api-version=2023-07-01")
                .then().statusCode(200);

        given().when().get(base + "?api-version=2023-07-01")
                .then().statusCode(404);
    }
}
