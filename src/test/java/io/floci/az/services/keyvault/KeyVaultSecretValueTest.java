package io.floci.az.services.keyvault;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The secret read another service uses to resolve a Key Vault reference, over secrets written through the data plane. */
@QuarkusTest
class KeyVaultSecretValueTest {

    @Inject
    KeyVaultHandler keyVault;

    @Test
    void aSecretReadsBackByNameAndByVersionUntilItIsDisabled() {
        String vaultName = "kv" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String vault = "/" + vaultName + "-keyvault/";
        String first = putSecret(vault, "first");
        putSecret(vault, "second");

        assertEquals(Optional.of("second"), keyVault.secretValue(vaultName, "token", null));
        assertEquals(Optional.of("first"), keyVault.secretValue(vaultName, "token", first));
        assertEquals(Optional.empty(), keyVault.secretValue(vaultName, "absent", null));
        assertEquals(Optional.empty(), keyVault.secretValue(vaultName, "token", "0000000000000000"));

        given().header("Authorization", "Bearer test-token")
                .contentType("application/json")
                .body(Map.of("attributes", Map.of("enabled", false)))
                .when().patch(vault + "secrets/token/" + first + "?api-version=7.4")
                .then().statusCode(200);
        assertEquals(Optional.empty(), keyVault.secretValue(vaultName, "token", first));
    }

    /** Writes a new version of {@code token} and returns its version id. */
    private static String putSecret(String vault, String value) {
        String id = given().header("Authorization", "Bearer test-token")
                .contentType("application/json")
                .body(Map.of("value", value))
                .when().put(vault + "secrets/token?api-version=7.4")
                .then().statusCode(200)
                .extract().path("id");
        return id.substring(id.lastIndexOf('/') + 1);
    }
}
