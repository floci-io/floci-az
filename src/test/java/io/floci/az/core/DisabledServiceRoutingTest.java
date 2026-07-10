package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * A disabled service must answer {@code 503 ServiceDisabled} on every URL form that unambiguously
 * names it — not just the account-suffix form.
 *
 * <p>Before this was fixed, {@link AzureServiceRegistry#resolve} collapsed "no handler" and "service
 * disabled" into the same empty {@code Optional}. The host-routed and ARM-base Key Vault stages
 * therefore declined, and the request fell through to the account-suffix terminal, which read
 * {@code secrets} as a <em>storage account name</em> and handed it to the blob handler — producing a
 * {@code 501 NotImplemented} storage error for a disabled Key Vault. Only the account-suffix form
 * reached the {@code isKnown && !isEnabled} check and answered 503.</p>
 */
@QuarkusTest
@TestProfile(DisabledServiceRoutingTest.KeyVaultDisabled.class)
@DisplayName("Routing — a disabled service answers 503 on every URL form that names it")
class DisabledServiceRoutingTest {

    public static class KeyVaultDisabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.key-vault.enabled", "false");
        }
    }

    @Test
    void hostRoutedKeyVaultReportsDisabled() {
        given().header("Host", "myvault.vault.azure.net")
                .when().get("/secrets/foo?api-version=7.4")
                .then().statusCode(503)
                .body(containsString("ServiceDisabled"))
                .body(containsString("keyvault"));
    }

    @Test
    void armBaseKeyVaultReportsDisabled() {
        given().when().get("/secrets/foo?api-version=7.4")
                .then().statusCode(503)
                .body(containsString("ServiceDisabled"));
    }

    @Test
    void accountSuffixKeyVaultReportsDisabled() {
        given().when().get("/devstoreaccount1-keyvault/secrets/foo?api-version=7.4")
                .then().statusCode(503)
                .body(containsString("ServiceDisabled"));
    }

    /**
     * Disabling Key Vault must not disturb unrelated routing: a genuine storage account keeps working,
     * including one whose name merely begins with a Key Vault collection name.
     */
    @Test
    void disablingKeyVaultLeavesStorageRoutingIntact() {
        given().when().get("/devstoreaccount1/?comp=list")
                .then().statusCode(200)
                .body(containsString("EnumerationResults"));
        given().when().get("/secretsaccount/?comp=list")
                .then().statusCode(200)
                .body(containsString("EnumerationResults"));
    }
}
