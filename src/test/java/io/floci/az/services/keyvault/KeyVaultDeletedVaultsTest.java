package io.floci.az.services.keyvault;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * {@code Vaults_GetDeleted}, {@code Vaults_ListDeleted}, {@code Vaults_PurgeDeleted} and {@code createMode: recover}.
 * azurerm purges a vault after deleting it whenever the vault reports {@code enableSoftDelete}, so without these
 * {@code terraform destroy} of any key vault fails.
 */
@QuarkusTest
@DisplayName("Key Vault ARM: a deleted vault can be read, recovered and purged")
class KeyVaultDeletedVaultsTest {

    private static final String API = "?api-version=2023-02-01";
    private static final String SUB = "test-sub-kv-deleted";
    private static final String NAME = "kv-deleted";
    private static final String VAULT = "/subscriptions/" + SUB + "/resourceGroups/test-rg-kv-deleted"
        + "/providers/Microsoft.KeyVault/vaults/" + NAME;
    private static final String DELETED = "/subscriptions/" + SUB
        + "/providers/Microsoft.KeyVault/locations/eastus/deletedVaults/" + NAME;
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static void putVault(String location, String properties) {
        given().contentType("application/json")
            .body("""
                {"location": "%s", "tags": {"env": "test"}, "properties": {
                  "tenantId": "%s", "sku": {"family": "A", "name": "standard"}%s}}
                """.formatted(location, TENANT, properties))
            .when().put(VAULT + API)
            .then().statusCode(200);
    }

    private static void deleteVault() {
        given().when().delete(VAULT + API).then().statusCode(200);
    }

    @Test
    void aDeletedVaultAnswersInTheSpecShape() {
        putVault("eastus", ", \"accessPolicies\": [], \"softDeleteRetentionInDays\": 7");
        deleteVault();

        given().when().get(VAULT + API).then().statusCode(404);
        given().when().get(DELETED + API)
            .then().statusCode(200)
            .body("id", equalTo(DELETED))
            .body("name", equalTo(NAME))
            .body("type", equalTo("Microsoft.KeyVault/deletedVaults"))
            .body("properties.vaultId", equalTo(VAULT))
            .body("properties.location", equalTo("eastus"))
            .body("properties.tags.env", equalTo("test"))
            .body("properties.deletionDate", matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"))
            .body("properties.scheduledPurgeDate", matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"))
            .body("properties", not(hasKey("purgeProtectionEnabled")))
            .body("$", not(hasKey("vault")));
    }

    @Test
    void theListNamesTheSubscriptionsDeletedVaultsOnly() {
        putVault("eastus", ", \"accessPolicies\": []");
        deleteVault();

        given().when().get("/subscriptions/" + SUB + "/providers/Microsoft.KeyVault/deletedVaults" + API)
            .then().statusCode(200)
            .body("value.name", contains(NAME))
            .body("value[0]", not(hasKey("vault")));
        given().when().get("/subscriptions/test-sub-kv-other/providers/Microsoft.KeyVault/deletedVaults" + API)
            .then().statusCode(200)
            .body("value", empty());
    }

    /** {@code az keyvault list-deleted} lists managed HSMs too, and fails outright if that list is not served. */
    @Test
    void theDeletedManagedHsmListIsEmpty() {
        given().when().get("/subscriptions/" + SUB + "/providers/Microsoft.KeyVault/deletedManagedHSMs" + API)
            .then().statusCode(200)
            .body("value", empty());
    }

    @Test
    void aDisplayNameLocationIsAddressedByItsName() {
        putVault("East US", ", \"accessPolicies\": []");
        deleteVault();

        given().when().get(DELETED + API).then().statusCode(200);
    }

    @Test
    void anotherLocationDoesNotFindIt() {
        putVault("eastus", ", \"accessPolicies\": []");
        deleteVault();

        given().when().get(DELETED.replace("/eastus/", "/westus/") + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void aPurgeRemovesItAndASecondPurgeIsNotFound() {
        putVault("eastus", ", \"accessPolicies\": []");
        deleteVault();

        given().when().post(DELETED + "/purge" + API).then().statusCode(200);

        given().when().get(DELETED + API).then().statusCode(404);
        given().when().post(DELETED + "/purge" + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void aVaultWithPurgeProtectionIsNotPurged() {
        putVault("eastus", ", \"accessPolicies\": [], \"enablePurgeProtection\": true");
        deleteVault();

        given().when().post(DELETED + "/purge" + API)
            .then().statusCode(409)
            .body("error.code", equalTo("Conflict"));
        given().when().get(DELETED + API)
            .then().statusCode(200)
            .body("properties.purgeProtectionEnabled", equalTo(true));
    }

    @Test
    void aVaultWithoutSoftDeleteLeavesNothingBehind() {
        putVault("eastus", ", \"accessPolicies\": [], \"enableSoftDelete\": false");
        deleteVault();

        given().when().get(DELETED + API).then().statusCode(404);
    }

    @Test
    void aRecoverRestoresWhatTheRequestDoesNotSet() {
        putVault("eastus", """
            , "enableRbacAuthorization": true, "accessPolicies": [{
              "tenantId": "%s", "objectId": "object-1", "permissions": {"secrets": ["get"]}}]
            """.formatted(TENANT));
        deleteVault();

        given().contentType("application/json")
            .body("""
                {"location": "eastus", "properties": {
                  "tenantId": "%s", "sku": {"family": "A", "name": "standard"}, "createMode": "recover"}}
                """.formatted(TENANT))
            .when().put(VAULT + API)
            .then().statusCode(200);

        given().when().get(VAULT + API)
            .then().statusCode(200)
            .body("tags.env", equalTo("test"))
            .body("properties.enableRbacAuthorization", equalTo(true))
            .body("properties.accessPolicies.objectId", contains("object-1"))
            .body("properties", not(hasKey("createMode")));
        given().when().get(DELETED + API).then().statusCode(404);
    }

    @Test
    void aPlainCreateOverADeletedNameDiscardsTheRecord() {
        putVault("eastus", ", \"accessPolicies\": [], \"enableRbacAuthorization\": true");
        deleteVault();

        putVault("eastus", ", \"accessPolicies\": []");

        given().when().get(VAULT + API)
            .then().statusCode(200)
            .body("properties", not(hasKey("enableRbacAuthorization")));
        given().when().get(DELETED + API).then().statusCode(404);
    }

    @Test
    void aRecoverWithNothingDeletedIsAnOrdinaryCreate() {
        putVault("eastus", ", \"accessPolicies\": [], \"createMode\": \"recover\"");

        given().when().get(VAULT + API).then().statusCode(200);
    }

    @Test
    void theWrongMethodIsRefused() {
        given().contentType("application/json").body("{}").when().put(DELETED + API).then().statusCode(405);
        given().when().get(DELETED + "/purge" + API).then().statusCode(405);
        given().when().delete("/subscriptions/" + SUB + "/providers/Microsoft.KeyVault/deletedVaults" + API)
            .then().statusCode(405);
    }

    @Test
    void theResetClearsDeletedVaults() {
        putVault("eastus", ", \"accessPolicies\": []");
        deleteVault();

        given().post("/_admin/reset").then().statusCode(204);

        given().when().get(DELETED + API).then().statusCode(404);
    }
}
