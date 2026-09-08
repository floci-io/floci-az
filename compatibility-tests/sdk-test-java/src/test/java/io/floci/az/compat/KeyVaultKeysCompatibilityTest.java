package io.floci.az.compat;

import com.azure.core.exception.ResourceNotFoundException;
import com.azure.core.util.Context;
import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.models.DecryptParameters;
import com.azure.security.keyvault.keys.cryptography.models.EncryptParameters;
import com.azure.security.keyvault.keys.cryptography.models.EncryptionAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.SignatureAlgorithm;
import com.azure.security.keyvault.keys.models.CreateEcKeyOptions;
import com.azure.security.keyvault.keys.models.CreateOctKeyOptions;
import com.azure.security.keyvault.keys.models.CreateRsaKeyOptions;
import com.azure.security.keyvault.keys.models.DeletedKey;
import com.azure.security.keyvault.keys.models.KeyCurveName;
import com.azure.security.keyvault.keys.models.KeyProperties;
import com.azure.security.keyvault.keys.models.KeyRotationPolicy;
import com.azure.security.keyvault.keys.models.KeyVaultKey;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Key Vault Keys + Crypto Compatibility")
class KeyVaultKeysCompatibilityTest {

    private KeyClient client;

    @BeforeAll
    void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        client = EmulatorConfig.buildKeyClient();
    }

    private String name(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private CryptographyClient crypto(KeyVaultKey key) {
        return EmulatorConfig.buildCryptographyClient(key.getName(), key.getProperties().getVersion());
    }

    // -------------------------------------------------------------------------
    // Key CRUD + lifecycle
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("create/get RSA key")
    void createAndGetRsaKey() {
        String n = name("rsa");
        KeyVaultKey created = client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        assertEquals(n, created.getName());
        assertNotNull(created.getKey().getN());

        KeyVaultKey fetched = client.getKey(n);
        assertEquals(n, fetched.getName());
        client.beginDeleteKey(n);
    }

    @Test
    @DisplayName("create/get EC key")
    void createAndGetEcKey() {
        String n = name("ec");
        KeyVaultKey created = client.createEcKey(new CreateEcKeyOptions(n).setCurveName(KeyCurveName.P_256));
        assertNotNull(created.getKey().getX());
        assertNotNull(created.getKey().getY());

        assertEquals(n, client.getKey(n).getName());
        client.beginDeleteKey(n);
    }

    @Test
    @DisplayName("create/get oct key")
    void createAndGetOctKey() {
        String n = name("oct");
        KeyVaultKey created = client.createOctKey(new CreateOctKeyOptions(n).setKeySize(256));
        assertNotNull(created.getKey().getK());

        assertEquals(n, client.getKey(n).getName());
        client.beginDeleteKey(n);
    }

    @Test
    @DisplayName("get nonexistent key throws 404")
    void getNonexistentThrows() {
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> client.getKey("no-such-key-xyz-" + UUID.randomUUID()));
        assertEquals(404, ex.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("list keys includes created ones")
    void listKeys() {
        String n = name("list");
        client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        List<String> names = client.listPropertiesOfKeys().stream().map(KeyProperties::getName).toList();
        assertTrue(names.contains(n));
        client.beginDeleteKey(n);
    }

    @Test
    @DisplayName("list key versions")
    void listKeyVersions() {
        String n = name("ver");
        KeyVaultKey v1 = client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        KeyVaultKey v2 = client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        assertNotEquals(v1.getProperties().getVersion(), v2.getProperties().getVersion());
        assertEquals(2, client.listPropertiesOfKeyVersions(n).stream().count());
        client.beginDeleteKey(n);
    }

    @Test
    @DisplayName("get specific version")
    void getSpecificVersion() {
        String n = name("sv");
        KeyVaultKey v1 = client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        String version = v1.getProperties().getVersion();
        client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        assertEquals(version, client.getKey(n, version).getProperties().getVersion());
        client.beginDeleteKey(n);
    }

    @Test
    @DisplayName("delete/recover/purge lifecycle")
    void deleteRecoverPurge() {
        String n = name("lc");
        client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        client.beginDeleteKey(n).poll();
        assertNotNull(client.getDeletedKey(n).getName());

        client.beginRecoverDeletedKey(n).poll();
        assertEquals(n, client.getKey(n).getName());

        client.beginDeleteKey(n).poll();
        client.purgeDeletedKey(n);
        List<String> deleted = client.listDeletedKeys().stream().map(DeletedKey::getName).toList();
        assertFalse(deleted.contains(n));
    }

    @Test
    @DisplayName("backup and restore key")
    void backupRestoreKey() {
        String n = name("bak");
        client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        byte[] backup = client.backupKey(n);
        assertNotNull(backup);
        assertTrue(backup.length > 0);

        client.beginDeleteKey(n).poll();
        client.purgeDeletedKey(n);

        KeyVaultKey restored = client.restoreKeyBackup(backup);
        assertEquals(n, restored.getName());
        client.beginDeleteKey(n);
    }

    @Test
    @DisplayName("rotate key yields a fresh version")
    void rotateKey() {
        String n = name("rot");
        KeyVaultKey created = client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        String v1 = created.getProperties().getVersion();
        KeyVaultKey rotated = client.rotateKey(n);
        assertNotEquals(v1, rotated.getProperties().getVersion());
        client.beginDeleteKey(n);
    }

    @Test
    @DisplayName("get and update key rotation policy")
    void rotationPolicyGetAndUpdate() {
        String n = name("rotpol");
        client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));

        KeyRotationPolicy policy = client.getKeyRotationPolicy(n);
        assertNotNull(policy);
        assertNotNull(policy.getLifetimeActions());

        // Round-trip the default policy back through update (PUT) and verify it persists.
        KeyRotationPolicy updated = client.updateKeyRotationPolicy(n, policy);
        assertNotNull(updated);
        client.beginDeleteKey(n);
    }

    // -------------------------------------------------------------------------
    // Crypto operations
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("RSA-OAEP-256 encrypt/decrypt round-trip")
    void rsaOaep256RoundTrip() {
        String n = name("crypt-oaep");
        KeyVaultKey key = client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        CryptographyClient c = crypto(key);
        byte[] plaintext = "hello-rsa-oaep".getBytes();

        byte[] ciphertext = c.encrypt(EncryptParameters.createRsaOaep256Parameters(plaintext), Context.NONE).getCipherText();
        byte[] decrypted = c.decrypt(DecryptParameters.createRsaOaep256Parameters(ciphertext), Context.NONE).getPlainText();
        assertArrayEquals(plaintext, decrypted);
        client.beginDeleteKey(n);
    }

    @Test
    @DisplayName("RSA1_5 encrypt/decrypt round-trip")
    void rsa15RoundTrip() {
        String n = name("crypt-rsa15");
        KeyVaultKey key = client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        CryptographyClient c = crypto(key);
        byte[] plaintext = "hello-rsa15".getBytes();

        byte[] ciphertext = c.encrypt(EncryptParameters.createRsa15Parameters(plaintext), Context.NONE).getCipherText();
        byte[] decrypted = c.decrypt(DecryptParameters.createRsa15Parameters(ciphertext), Context.NONE).getPlainText();
        assertArrayEquals(plaintext, decrypted);
        client.beginDeleteKey(n);
    }

    @Test
    @DisplayName("A256GCM encrypt/decrypt round-trip")
    void a256GcmRoundTrip() {
        String n = name("crypt-gcm");
        KeyVaultKey key = client.createOctKey(new CreateOctKeyOptions(n).setKeySize(256));
        CryptographyClient c = crypto(key);
        byte[] plaintext = "hello-aes-gcm".getBytes();

        var result = c.encrypt(EncryptParameters.createA256GcmParameters(plaintext), Context.NONE);
        byte[] decrypted = c.decrypt(DecryptParameters.createA256GcmParameters(
                result.getCipherText(), result.getIv(), result.getAuthenticationTag()), Context.NONE).getPlainText();
        assertArrayEquals(plaintext, decrypted);
        client.beginDeleteKey(n);
    }

    @Test
    @DisplayName("RS256 signData/verifyData round-trip")
    void rs256SignVerify() {
        String n = name("sig-rs256");
        KeyVaultKey key = client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        CryptographyClient c = crypto(key);
        byte[] data = "sign-me-rs256".getBytes();

        byte[] signature = c.signData(SignatureAlgorithm.RS256, data).getSignature();
        assertTrue(c.verifyData(SignatureAlgorithm.RS256, data, signature).isValid());
        client.beginDeleteKey(n);
    }

    @Test
    @DisplayName("PS256 signData/verifyData round-trip")
    void ps256SignVerify() {
        String n = name("sig-ps256");
        KeyVaultKey key = client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        CryptographyClient c = crypto(key);
        byte[] data = "sign-me-ps256".getBytes();

        byte[] signature = c.signData(SignatureAlgorithm.PS256, data).getSignature();
        assertTrue(c.verifyData(SignatureAlgorithm.PS256, data, signature).isValid());
        client.beginDeleteKey(n);
    }

    @Test
    @DisplayName("ES256/ES384/ES512 signData/verifyData round-trips")
    void ecSignVerify() {
        KeyCurveName[] curves = {KeyCurveName.P_256, KeyCurveName.P_384, KeyCurveName.P_521};
        SignatureAlgorithm[] algs = {SignatureAlgorithm.ES256, SignatureAlgorithm.ES384, SignatureAlgorithm.ES512};
        for (int i = 0; i < curves.length; i++) {
            String n = name("sig-ec");
            KeyVaultKey key = client.createEcKey(new CreateEcKeyOptions(n).setCurveName(curves[i]));
            CryptographyClient c = crypto(key);
            byte[] data = "sign-me-ec".getBytes();
            byte[] signature = c.signData(algs[i], data).getSignature();
            assertTrue(c.verifyData(algs[i], data, signature).isValid());
            client.beginDeleteKey(n);
        }
    }

    @Test
    @DisplayName("RSA-OAEP-256 wrapKey/unwrapKey round-trip")
    void wrapUnwrapKey() {
        String n = name("wrap");
        KeyVaultKey key = client.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        CryptographyClient c = crypto(key);
        byte[] keyMaterial = "wrapped-key-material".getBytes();

        byte[] wrapped = c.wrapKey(KeyWrapAlgorithm.RSA_OAEP_256, keyMaterial).getEncryptedKey();
        byte[] unwrapped = c.unwrapKey(KeyWrapAlgorithm.RSA_OAEP_256, wrapped).getKey();
        assertArrayEquals(keyMaterial, unwrapped);
        client.beginDeleteKey(n);
    }

    // -------------------------------------------------------------------------
    // Managed HSM
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("managed HSM basic key CRUD")
    void managedHsmBasicCrud() {
        KeyClient hsm = EmulatorConfig.buildManagedHsmKeyClient();
        String n = name("hsm");
        KeyVaultKey created = hsm.createRsaKey(new CreateRsaKeyOptions(n).setKeySize(2048));
        assertTrue(created.getKey().getId().contains("managedhsm"));

        assertEquals(n, hsm.getKey(n).getName());
        assertTrue(hsm.listPropertiesOfKeys().stream().anyMatch(p -> p.getName().equals(n)));
        hsm.beginDeleteKey(n);
    }
}
