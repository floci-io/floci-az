package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@DisplayName("ARM storage accounts")
class ArmStorageAccountTest {

    @Test
    void rejectsInvalidStorageAccountName() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\"}")
            .when().put("/subscriptions/sub-invalid-name/resourceGroups/rg-name-owner"
                    + "/providers/Microsoft.Storage/storageAccounts/flociupper1703?api-version=2023-01-01")
            .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\"}")
            .when().put("/subscriptions/sub-invalid-name/resourceGroups/rg-invalid-name"
                    + "/providers/Microsoft.Storage/storageAccounts/FlociUpper1703?api-version=2023-01-01")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("AccountNameInvalid"))
            .body("error.message", equalTo("FlociUpper1703 is not a valid storage account name. "
                    + "Storage account name must be between 3 and 24 characters in length "
                    + "and use numbers and lower-case letters only."));
    }

    @Test
    void storageAccountPrimaryEndpointsIncludeDfs() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\"}")
            .when().put("/subscriptions/sub-cred-vending/resourceGroups/rg-cred-vending"
                    + "/providers/Microsoft.Storage/storageAccounts/credvendingacct?api-version=2023-01-01")
            .then()
            .statusCode(200)
            .body("properties.primaryEndpoints.blob",
                    equalTo("http://credvendingacct.blob.core.windows.net/"))
            .body("properties.primaryEndpoints.dfs",
                    equalTo("http://credvendingacct.dfs.core.windows.net/"));
    }

    @Test
    void storageAccountHierarchicalNamespaceControlsDfsAccessControl() {
        given()
            .contentType("application/json")
            .body("""
                    {
                      "location": "eastus",
                      "properties": {"isHnsEnabled": true}
                    }
                    """)
            .when().put("/subscriptions/sub-hns/resourceGroups/rg-hns"
                    + "/providers/Microsoft.Storage/storageAccounts/armhnsaccount?api-version=2023-01-01")
            .then()
            .statusCode(200)
            .body("properties.isHnsEnabled", equalTo(true));

        given().put("/armhnsaccount/hns-filesystem?restype=container");
        given()
            .header("Host", "armhnsaccount.dfs.core.windows.net")
            .queryParam("action", "getAccessControl")
            .when().head("/hns-filesystem")
            .then()
            .statusCode(200)
            .header("x-ms-acl", not(isEmptyOrNullString()));

        given()
            .contentType("application/json")
            .body("""
                    {
                      "location": "eastus",
                      "properties": {"isHnsEnabled": false}
                    }
                    """)
            .when().put("/subscriptions/sub-hns/resourceGroups/rg-hns"
                    + "/providers/Microsoft.Storage/storageAccounts/armflataccount?api-version=2023-01-01")
            .then()
            .statusCode(200)
            .body("properties.isHnsEnabled", equalTo(false));

        given().put("/armflataccount/flat-filesystem?restype=container");
        given()
            .header("Host", "armflataccount.dfs.core.windows.net")
            .queryParam("action", "getAccessControl")
            .when().head("/flat-filesystem")
            .then()
            .statusCode(400)
            .header("x-ms-error-code", equalTo("HierarchicalNamespaceNotEnabled"));
    }

    @Test
    void storageAccountHierarchicalNamespaceCannotBeChanged() {
        String path = "/subscriptions/sub-immutable/resourceGroups/rg-immutable"
                + "/providers/Microsoft.Storage/storageAccounts/immutablehns?api-version=2023-01-01";

        given()
            .contentType("application/json")
            .body("""
                    {
                      "location": "eastus",
                      "properties": {"isHnsEnabled": true}
                    }
                    """)
            .when().put(path)
            .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("""
                    {
                      "location": "eastus",
                      "properties": {"isHnsEnabled": false}
                    }
                    """)
            .when().put(path)
            .then()
            .statusCode(400)
            .body("error.code", equalTo("AccountPropertyCannotBeUpdated"))
            .body("error.message", equalTo("The property 'isHnsEnabled' was specified in the input, but it cannot "
                    + "be updated as it is read-only. For more information, see - "
                    + "https://aka.ms/storageaccountupdate"));

        given()
            .contentType("application/json")
            .body("""
                    {
                      "location": "eastus",
                      "properties": {"isHnsEnabled": true}
                    }
                    """)
            .when().put(path)
            .then()
            .statusCode(200)
            .body("properties.isHnsEnabled", equalTo(true));

        String flatPath = "/subscriptions/sub-immutable/resourceGroups/rg-immutable"
                + "/providers/Microsoft.Storage/storageAccounts/immutableflat?api-version=2023-01-01";
        given()
            .contentType("application/json")
            .body("""
                    {
                      "location": "eastus",
                      "properties": {"isHnsEnabled": false}
                    }
                    """)
            .when().put(flatPath)
            .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("""
                    {
                      "location": "eastus",
                      "properties": {"isHnsEnabled": true}
                    }
                    """)
            .when().put(flatPath)
            .then()
            .statusCode(400)
            .body("error.code", equalTo("AccountPropertyCannotBeUpdated"));
    }

    @Test
    void deletingMissingStorageAccountDoesNotChangeConfiguredHierarchicalNamespace() {
        given().put("/devstoreaccount1/delete-missing-filesystem?restype=container");

        given()
            .when().delete("/subscriptions/sub-missing/resourceGroups/rg-missing"
                    + "/providers/Microsoft.Storage/storageAccounts/devstoreaccount1?api-version=2023-01-01")
            .then()
            .statusCode(200);

        given()
            .header("Host", "devstoreaccount1.dfs.core.windows.net")
            .queryParam("action", "getAccessControl")
            .when().head("/delete-missing-filesystem")
            .then()
            .statusCode(200);
    }

    @Test
    void deletingStorageAccountRestoresConfiguredHierarchicalNamespace() {
        given()
            .contentType("application/json")
            .body("""
                    {
                      "location": "eastus",
                      "properties": {"isHnsEnabled": false}
                    }
                    """)
            .when().put("/subscriptions/sub-configured/resourceGroups/rg-configured"
                    + "/providers/Microsoft.Storage/storageAccounts/devstoreaccount1?api-version=2023-01-01")
            .then()
            .statusCode(200)
            .body("properties.isHnsEnabled", equalTo(false));

        given().put("/devstoreaccount1/configured-filesystem?restype=container");
        given()
            .header("Host", "devstoreaccount1.dfs.core.windows.net")
            .queryParam("action", "getAccessControl")
            .when().head("/configured-filesystem")
            .then()
            .statusCode(400)
            .header("x-ms-error-code", equalTo("HierarchicalNamespaceNotEnabled"));

        given()
            .when().delete("/subscriptions/sub-configured/resourceGroups/rg-other"
                    + "/providers/Microsoft.Storage/storageAccounts/devstoreaccount1?api-version=2023-01-01")
            .then()
            .statusCode(200);

        given()
            .header("Host", "devstoreaccount1.dfs.core.windows.net")
            .queryParam("action", "getAccessControl")
            .when().head("/configured-filesystem")
            .then()
            .statusCode(400)
            .header("x-ms-error-code", equalTo("HierarchicalNamespaceNotEnabled"));

        given()
            .when().delete("/subscriptions/sub-configured/resourceGroups/rg-configured"
                    + "/providers/Microsoft.Storage/storageAccounts/devstoreaccount1?api-version=2023-01-01")
            .then()
            .statusCode(200);

        given()
            .header("Host", "devstoreaccount1.dfs.core.windows.net")
            .queryParam("action", "getAccessControl")
            .when().head("/configured-filesystem")
            .then()
            .statusCode(200);
    }

    @Test
    void resetClearsHierarchicalNamespaceOverrides() {
        given()
            .contentType("application/json")
            .body("""
                    {
                      "location": "eastus",
                      "properties": {"isHnsEnabled": true}
                    }
                    """)
            .when().put("/subscriptions/sub-reset/resourceGroups/rg-reset"
                    + "/providers/Microsoft.Storage/storageAccounts/resetaccount?api-version=2023-01-01")
            .then()
            .statusCode(200);

        given().put("/resetaccount/reset-filesystem?restype=container");
        given()
            .header("Host", "resetaccount.dfs.core.windows.net")
            .queryParam("action", "getAccessControl")
            .when().head("/reset-filesystem")
            .then()
            .statusCode(200);

        given().post("/_admin/reset").then().statusCode(204);

        given()
            .when().get("/subscriptions/sub-reset/resourceGroups/rg-reset"
                    + "/providers/Microsoft.Storage/storageAccounts/resetaccount?api-version=2023-01-01")
            .then()
            .statusCode(404);

        given().put("/resetaccount/reset-filesystem?restype=container");
        given()
            .header("Host", "resetaccount.dfs.core.windows.net")
            .queryParam("action", "getAccessControl")
            .when().head("/reset-filesystem")
            .then()
            .statusCode(400)
            .header("x-ms-error-code", equalTo("HierarchicalNamespaceNotEnabled"));
    }

    @Test
    void invalidStorageAccountNameIsReportedUnavailable() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\"}")
            .when().put("/subscriptions/sub-name/resourceGroups/rg-name"
                    + "/providers/Microsoft.Storage/storageAccounts/flociupper1704?api-version=2023-01-01")
            .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{\"name\":\"FlociUpper1704\",\"type\":\"Microsoft.Storage/storageAccounts\"}")
            .when().post("/subscriptions/sub-name/providers/Microsoft.Storage/checkNameAvailability"
                    + "?api-version=2023-01-01")
            .then()
            .statusCode(200)
            .body("nameAvailable", equalTo(false))
            .body("reason", equalTo("AccountNameInvalid"))
            .body("message", equalTo("FlociUpper1704 is not a valid storage account name. Storage account name "
                    + "must be between 3 and 24 characters in length and use numbers and lower-case letters only."));
    }
}
