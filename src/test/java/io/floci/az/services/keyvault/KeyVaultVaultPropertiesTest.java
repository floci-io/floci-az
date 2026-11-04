package io.floci.az.services.keyvault;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * {@code Vaults_CreateOrUpdate} and {@code Vaults_Update} read back what the client wrote. azurerm reads these
 * properties after every write: a dropped one is a perpetual diff, and a dropped {@code publicNetworkAccess} makes it
 * treat a private vault as public.
 */
@QuarkusTest
@DisplayName("Key Vault ARM: a vault reads back the properties written to it")
class KeyVaultVaultPropertiesTest {

    private static final String API = "?api-version=2026-02-01";
    private static final String VAULT = "/subscriptions/test-sub-kv-props/resourceGroups/test-rg-kv-props"
        + "/providers/Microsoft.KeyVault/vaults/kv-props";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";

    private static final String FULL_BODY = """
        {
          "location": "swedencentral",
          "tags": {"env": "test"},
          "properties": {
            "tenantId": "%s",
            "sku": {"family": "A", "name": "premium"},
            "accessPolicies": [],
            "createMode": "default",
            "enabledForDeployment": true,
            "enabledForDiskEncryption": true,
            "enabledForTemplateDeployment": true,
            "enableSoftDelete": true,
            "softDeleteRetentionInDays": 7,
            "enableRbacAuthorization": true,
            "enablePurgeProtection": true,
            "publicNetworkAccess": "Disabled",
            "networkAcls": {"bypass": "AzureServices", "defaultAction": "Deny"},
            "vaultUri": "https://elsewhere.example/",
            "provisioningState": "Failed"
          }
        }
        """.formatted(TENANT);

    private static final String MINIMAL_BODY = """
        {
          "location": "eastus",
          "properties": {
            "tenantId": "%s",
            "sku": {"family": "A", "name": "standard"},
            "accessPolicies": []
          }
        }
        """.formatted(TENANT);

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static void put(String body) {
        given().contentType("application/json").body(body)
            .when().put(VAULT + API)
            .then().statusCode(200);
    }

    @Test
    void aGetReturnsEverySettingThePutWrote() {
        put(FULL_BODY);

        given().when().get(VAULT + API)
            .then().statusCode(200)
            .body("tags.env", equalTo("test"))
            .body("properties.sku.name", equalTo("premium"))
            .body("properties.enabledForDeployment", equalTo(true))
            .body("properties.enabledForDiskEncryption", equalTo(true))
            .body("properties.enabledForTemplateDeployment", equalTo(true))
            .body("properties.enableSoftDelete", equalTo(true))
            .body("properties.softDeleteRetentionInDays", equalTo(7))
            .body("properties.enableRbacAuthorization", equalTo(true))
            .body("properties.enablePurgeProtection", equalTo(true))
            .body("properties.publicNetworkAccess", equalTo("Disabled"))
            .body("properties.networkAcls.defaultAction", equalTo("Deny"));
    }

    @Test
    void thePropertiesTheServiceGeneratesAreNotTakenFromTheBody() {
        put(FULL_BODY);

        given().when().get(VAULT + API)
            .then().statusCode(200)
            .body("properties.vaultUri", equalTo("https://kv-props.vault.azure.net/"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties", not(hasKey("createMode")));
    }

    @Test
    void aMinimalPutEnablesSoftDeleteAndOmitsWhatWasNotSent() {
        put(MINIMAL_BODY);

        given().when().get(VAULT + API)
            .then().statusCode(200)
            .body("$", not(hasKey("tags")))
            .body("properties.sku.name", equalTo("standard"))
            .body("properties.enableSoftDelete", equalTo(true))
            .body("properties", not(hasKey("enableRbacAuthorization")))
            .body("properties", not(hasKey("publicNetworkAccess")))
            .body("properties", not(hasKey("enablePurgeProtection")));
    }

    @Test
    void aPatchChangesOnlyWhatItNames() {
        put(FULL_BODY);

        given().contentType("application/json")
            .body("{\"properties\":{\"publicNetworkAccess\":\"Enabled\",\"softDeleteRetentionInDays\":30}}")
            .when().patch(VAULT + API)
            .then().statusCode(200)
            .body("name", equalTo("kv-props"))
            .body("properties.publicNetworkAccess", equalTo("Enabled"));

        given().when().get(VAULT + API)
            .then().statusCode(200)
            .body("location", equalTo("swedencentral"))
            .body("tags.env", equalTo("test"))
            .body("properties.publicNetworkAccess", equalTo("Enabled"))
            .body("properties.softDeleteRetentionInDays", equalTo(30))
            .body("properties.enableRbacAuthorization", equalTo(true))
            .body("properties.sku.name", equalTo("premium"));
    }

    @Test
    void aPatchReplacesTagsAndSkuAndLeavesGeneratedPropertiesAlone() {
        put(FULL_BODY);

        given().contentType("application/json")
            .body("""
                {
                  "tags": {"team": "core"},
                  "properties": {
                    "sku": {"family": "A", "name": "standard"},
                    "vaultUri": "https://elsewhere.example/",
                    "provisioningState": "Failed"
                  }
                }
                """)
            .when().patch(VAULT + API)
            .then().statusCode(200);

        given().when().get(VAULT + API)
            .then().statusCode(200)
            .body("tags.team", equalTo("core"))
            .body("tags", not(hasKey("env")))
            .body("properties.sku.name", equalTo("standard"))
            .body("properties.vaultUri", equalTo("https://kv-props.vault.azure.net/"))
            .body("properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    void aPatchKeepsAccessPoliciesItDoesNotListAndReplacesOnesItDoes() {
        String policy = "{\"tenantId\":\"" + TENANT + "\",\"objectId\":\"object-1\","
            + "\"permissions\":{\"secrets\":[\"get\"]}}";
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{\"tenantId\":\"" + TENANT
                + "\",\"sku\":{\"family\":\"A\",\"name\":\"standard\"},\"accessPolicies\":[" + policy + "]}}")
            .when().put(VAULT + API)
            .then().statusCode(200);

        given().contentType("application/json").body("{\"properties\":{\"enablePurgeProtection\":true}}")
            .when().patch(VAULT + API)
            .then().statusCode(200)
            .body("properties.accessPolicies.size()", equalTo(1));

        given().contentType("application/json").body("{\"properties\":{\"accessPolicies\":[]}}")
            .when().patch(VAULT + API)
            .then().statusCode(200)
            .body("properties.accessPolicies.size()", equalTo(0));
    }

    @Test
    void aPatchOfAMissingVaultIsNotFoundAndCreatesNothing() {
        given().contentType("application/json").body("{\"properties\":{\"enablePurgeProtection\":true}}")
            .when().patch(VAULT + API)
            .then().statusCode(404);

        given().when().get(VAULT + API).then().statusCode(404);
    }

    @Test
    void theResourceListCarriesTheVaultsTags() {
        put(FULL_BODY);

        given().when().get("/subscriptions/test-sub-kv-props/resources?api-version=2021-04-01")
            .then().statusCode(200)
            .body("value.find { it.name == 'kv-props' }.tags.env", equalTo("test"));
    }
}
