package io.floci.az.services.keyvault;

import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@DisplayName("Key Vault ARM: vaults and managed HSMs in the ARM resource store, names owned by the registry")
class KeyVaultResourceStoreTest {

    private static final String API = "?api-version=2023-07-01";
    private static final String VAULT_BODY = "{\"location\":\"eastus\",\"properties\":{\"tenantId\":"
        + "\"00000000-0000-0000-0000-000000000002\",\"sku\":{\"family\":\"A\",\"name\":\"standard\"}}}";
    private static final String HSM_BODY = "{\"location\":\"eastus\",\"sku\":{\"family\":\"B\",\"name\":\"Standard_B1\"},"
        + "\"properties\":{\"initialAdminObjectIds\":[\"o\"]}}";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String vault(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.KeyVault/vaults/" + name;
    }

    private static String hsm(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.KeyVault/managedHSMs/" + name;
    }

    private static void put(String path, String body, int status) {
        given().contentType("application/json").body(body).when().put(path + API).then().statusCode(status);
    }

    @Test
    void aVaultPutThroughOtherCasingUpdatesTheOneVault() {
        put(vault("sub-kvcase", "RG-Case", "CaseVault"), VAULT_BODY, 200);
        given().contentType("application/json").body(VAULT_BODY)
            .when().put(vault("SUB-KVCASE", "rg-case", "casevault") + API)
            .then().statusCode(200)
            .body("id", equalTo(vault("sub-kvcase", "RG-Case", "CaseVault")))
            .body("properties.vaultUri", equalTo("https://CaseVault.vault.azure.net/"));

        given().when().get("/subscriptions/sub-kvcase/resourceGroups/rg-case/providers/Microsoft.KeyVault/vaults" + API)
            .then().statusCode(200).body("value", hasSize(1));
        given().when().get("/subscriptions/sub-kvcase/providers/Microsoft.KeyVault/vaults" + API)
            .then().statusCode(200).body("value", hasSize(1));
        given().when().get("/subscriptions/sub-kvcase/resourceGroups/RG-CASE/resources" + API)
            .then().statusCode(200).body("value", hasSize(1))
            .body("value[0].properties.vaultUri", equalTo("https://CaseVault.vault.azure.net/"));
    }

    @Test
    void anHsmPutThroughOtherCasingUpdatesTheOneHsm() {
        put(hsm("sub-hsmcase", "RG-Case", "CaseHsm"), HSM_BODY, 200);
        given().contentType("application/json").body(HSM_BODY)
            .when().put(hsm("sub-hsmcase", "rg-case", "casehsm") + API)
            .then().statusCode(200)
            .body("id", equalTo(hsm("sub-hsmcase", "RG-Case", "CaseHsm")));

        given().when().get("/subscriptions/sub-hsmcase/providers/Microsoft.KeyVault/managedHSMs" + API)
            .then().statusCode(200).body("value", hasSize(1));
    }

    @Test
    void vaultsAndHsmsAreKeptUnderTheirCanonicalIdsInOneBackend() {
        put(vault("Sub-Stored", "RG", "StoredVault"), VAULT_BODY, 200);
        put(hsm("Sub-Stored", "RG", "StoredHsm"), HSM_BODY, 200);

        StorageBackend<String, StoredObject> backend = storageFactory.create(KeyVaultArmService.STORE);
        List<String> keys = backend.keys().stream().sorted().toList();
        assertEquals(List.of(hsm("Sub-Stored", "RG", "StoredHsm").toLowerCase(Locale.ROOT),
            vault("Sub-Stored", "RG", "StoredVault").toLowerCase(Locale.ROOT)), keys);

        given().when().delete(vault("sub-stored", "rg", "storedvault") + API).then().statusCode(200);
        given().when().get(hsm("Sub-Stored", "RG", "StoredHsm") + API).then().statusCode(200);
        assertEquals(List.of(hsm("Sub-Stored", "RG", "StoredHsm").toLowerCase(Locale.ROOT)),
            List.copyOf(backend.keys()));
    }

    @Test
    void aVaultNameBelongsToItsFirstOwnerWithTheVaultConflict() {
        put(vault("sub-kv-a", "rg", "ownedvault"), VAULT_BODY, 200);
        given().contentType("application/json").body(VAULT_BODY)
            .when().put(vault("sub-kv-b", "rg", "OwnedVault") + API)
            .then().statusCode(409)
            .body("error.code", equalTo("VaultAlreadyExists"))
            .body("error.message", equalTo("The vault name 'OwnedVault' is already in use. Vault names are "
                + "globally unique so it is possible that the name is already taken."));
        given().contentType("application/json").body(VAULT_BODY)
            .when().put(vault("sub-kv-a", "rg-other", "ownedvault") + API)
            .then().statusCode(409).body("error.code", equalTo("VaultAlreadyExists"));
    }

    @Test
    void anHsmNameBelongsToItsFirstOwnerWithTheHsmConflict() {
        put(hsm("sub-hsm-a", "rg", "ownedhsm"), HSM_BODY, 200);
        given().contentType("application/json").body(HSM_BODY)
            .when().put(hsm("sub-hsm-b", "rg", "ownedhsm") + API)
            .then().statusCode(409)
            .body("error.code", equalTo("Conflict"))
            .body("error.message", equalTo("The managed HSM name 'ownedhsm' is already in use."));
        // A vault and an HSM live under different DNS suffixes, so one name can be both.
        put(vault("sub-hsm-b", "rg", "ownedhsm"), VAULT_BODY, 200);
    }

    @Test
    void checkNameAvailabilityAnswersFromTheRegistry() {
        String check = "/subscriptions/sub-check/providers/Microsoft.KeyVault/checkNameAvailability" + API;
        String request = "{\"name\":\"checkvault\",\"type\":\"Microsoft.KeyVault/vaults\"}";

        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(true));
        put(vault("sub-owner", "rg", "checkvault"), VAULT_BODY, 200);
        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(false))
            .body("reason", equalTo("AlreadyExists"))
            .body("message", equalTo("The vault name 'checkvault' is already in use."));
        // ARM paths are case-insensitive: the lowercase provider action answers the same.
        given().contentType("application/json").body(request)
            .when().post("/subscriptions/sub-check/providers/microsoft.keyvault/checknameavailability" + API)
            .then().statusCode(200).body("nameAvailable", equalTo(false));
        given().when().delete(vault("sub-owner", "rg", "checkvault") + API).then().statusCode(200);
        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(true));
    }

    @Test
    void checkMhsmNameAvailabilityAnswersFromTheRegistry() {
        String check = "/subscriptions/sub-check/providers/Microsoft.KeyVault/checkMhsmNameAvailability" + API;
        String request = "{\"name\":\"checkhsm\"}";

        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(true));
        put(hsm("sub-owner", "rg", "checkhsm"), HSM_BODY, 200);
        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(false))
            .body("reason", equalTo("AlreadyExists"))
            .body("message", equalTo("The managed HSM name 'checkhsm' is already in use."));
        given().when().delete(hsm("sub-owner", "rg", "checkhsm") + API).then().statusCode(200);
        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(true));
    }

    @Test
    void aSubscriptionLevelPathNamingAVaultIsNotFound() {
        put(vault("sub-norg", "rg", "norgvault"), VAULT_BODY, 200);
        given().when().get("/subscriptions/sub-norg/providers/Microsoft.KeyVault/vaults/norgvault" + API)
            .then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void theKeyVaultFallbackAtTheArmBaseUsesTheOnlyVaultsName() {
        put(vault("sub-fallback", "rg", "onlyvault"), VAULT_BODY, 200);
        given().header("Authorization", "Bearer test-token").contentType("application/json")
            .body("{\"value\":\"v\"}")
            .when().put("/secrets/fallback-secret?api-version=7.4")
            .then().statusCode(200)
            .body("id", startsWith("https://onlyvault.vault.azure.net/secrets/fallback-secret/"));
    }
}
