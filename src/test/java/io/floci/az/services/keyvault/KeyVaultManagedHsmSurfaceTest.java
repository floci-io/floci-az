package io.floci.az.services.keyvault;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * A Managed HSM serves Keys, Administration and SecurityDomain; secrets and certificates belong to a
 * key vault. Both flavors route to this handler under the same account name, and the secret storage
 * keys carry no flavor, so an unguarded HSM route shares one namespace with the vault.
 *
 * <p>Every request here carries an {@code Authorization} header, because the bearer challenge is
 * returned before any route matching and would otherwise answer every case with a 401.</p>
 */
@QuarkusTest
class KeyVaultManagedHsmSurfaceTest {

    private static final String VAULT = "/hsmsurface-keyvault";
    private static final String HSM = "/hsmsurface-managedhsm";
    private static final String AUTH = "Bearer test-token";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @ParameterizedTest
    @ValueSource(strings = {"secrets", "secrets/s1", "deletedsecrets", "deletedsecrets/s1"})
    void secretsAreNotServedOnTheManagedHsmRoute(String path) {
        given().header("Authorization", AUTH)
                .get(HSM + "/" + path + "?api-version=7.4")
                .then().statusCode(404).body("error.code", is("NotFound"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"certificates", "certificates/contacts", "certificates/c1", "deletedcertificates"})
    void certificatesIncludingContactsAreNotServedOnTheManagedHsmRoute(String path) {
        given().header("Authorization", AUTH)
                .get(HSM + "/" + path + "?api-version=7.4")
                .then().statusCode(404).body("error.code", is("NotFound"));
    }

    @Test
    void writingASecretThroughTheManagedHsmRouteIsRefused() {
        given().header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"value\":\"from-hsm\"}")
                .put(HSM + "/secrets/shared?api-version=7.4")
                .then().statusCode(404).body("error.code", is("NotFound"));
    }

    @Test
    void hostStyleManagedHsmGetsTheSameAnswer() {
        given().header("Authorization", AUTH)
                .header("Host", "hsmsurface.managedhsm.azure.net:4577")
                .get("/secrets/s1?api-version=7.4")
                .then().statusCode(404).body("error.code", is("NotFound"));
    }

    @Test
    void vaultSecretsAreUnaffectedAndNotReachableThroughTheHsmRoute() {
        given().header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"value\":\"vault-only\"}")
                .put(VAULT + "/secrets/shared?api-version=7.4")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .get(VAULT + "/secrets/shared?api-version=7.4")
                .then().statusCode(200).body("value", is("vault-only"));

        given().header("Authorization", AUTH)
                .get(HSM + "/secrets/shared?api-version=7.4")
                .then().statusCode(404).body("error.code", is("NotFound"));
    }

    @Test
    void keysRemainServedOnBothFlavorsAndStayIsolated() {
        given().header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .post(HSM + "/keys/hsmkey/create?api-version=7.4")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .get(HSM + "/keys/hsmkey?api-version=7.4")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .get(VAULT + "/keys/hsmkey?api-version=7.4")
                .then().statusCode(404);
    }

    @Test
    void vaultCertificatesRemainServed() {
        given().header("Authorization", AUTH)
                .get(VAULT + "/certificates/contacts?api-version=7.4")
                .then().statusCode(200);
    }
}
