package io.floci.az.compat;

import com.azure.core.exception.ResourceNotFoundException;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.DeletedSecret;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.security.keyvault.secrets.models.SecretProperties;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Key Vault Secrets Compatibility")
class KeyVaultCompatibilityTest {

    private SecretClient client;

    @BeforeAll
    void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        client = EmulatorConfig.buildKeyVaultClient();
    }

    private String name(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    // -------------------------------------------------------------------------
    // Secret lifecycle
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("set and get secret")
    void setAndGetSecret() {
        String n = name("get");
        client.setSecret(n, "hello-world");
        KeyVaultSecret s = client.getSecret(n);
        assertEquals(n, s.getName());
        assertEquals("hello-world", s.getValue());
        assertNotNull(s.getProperties().getId());
        client.beginDeleteSecret(n);
    }

    @Test
    @DisplayName("set secret with content type")
    void setSecretWithContentType() {
        String n = name("ct");
        KeyVaultSecret secret = new KeyVaultSecret(n, "my-value")
                .setProperties(new SecretProperties().setContentType("text/plain"));
        client.setSecret(secret);
        KeyVaultSecret retrieved = client.getSecret(n);
        assertEquals("text/plain", retrieved.getProperties().getContentType());
        client.beginDeleteSecret(n);
    }

    @Test
    @DisplayName("set secret with tags")
    void setSecretWithTags() {
        String n = name("tags");
        KeyVaultSecret secret = new KeyVaultSecret(n, "tagged-value")
                .setProperties(new SecretProperties().setTags(Map.of("env", "test", "owner", "ci")));
        client.setSecret(secret);
        KeyVaultSecret retrieved = client.getSecret(n);
        assertEquals("test", retrieved.getProperties().getTags().get("env"));
        assertEquals("ci", retrieved.getProperties().getTags().get("owner"));
        client.beginDeleteSecret(n);
    }

    @Test
    @DisplayName("update overwrites value")
    void updateOverwritesValue() {
        String n = name("upd");
        client.setSecret(n, "v1");
        client.setSecret(n, "v2");
        assertEquals("v2", client.getSecret(n).getValue());
        client.beginDeleteSecret(n);
    }

    @Test
    @DisplayName("get nonexistent secret throws 404")
    void getNonexistentThrows() {
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> client.getSecret("no-such-secret-xyz-" + UUID.randomUUID()));
        assertEquals(404, ex.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("delete secret")
    void deleteSecret() {
        String n = name("del");
        client.setSecret(n, "to-be-deleted");
        client.beginDeleteSecret(n);
        DeletedSecret deleted = client.getDeletedSecret(n);
        assertEquals(n, deleted.getName());
    }

    @Test
    @DisplayName("delete nonexistent secret throws 404")
    void deleteNonexistentThrows() {
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> client.beginDeleteSecret("no-such-secret-xyz-" + UUID.randomUUID()).poll());
        assertEquals(404, ex.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("recover deleted secret")
    void recoverDeletedSecret() {
        String n = name("rec");
        client.setSecret(n, "recover-me");
        client.beginDeleteSecret(n);
        client.beginRecoverDeletedSecret(n).waitForCompletion();
        assertEquals("recover-me", client.getSecret(n).getValue());
        client.beginDeleteSecret(n);
    }

    @Test
    @DisplayName("purge deleted secret")
    void purgeDeletedSecret() {
        String n = name("purge");
        client.setSecret(n, "purge-me");
        client.beginDeleteSecret(n);
        client.purgeDeletedSecret(n);
        List<String> deleted = client.listDeletedSecrets().stream()
                .map(p -> p.getName()).toList();
        assertFalse(deleted.contains(n));
    }

    @Test
    @DisplayName("list secrets includes created ones")
    void listSecrets() {
        String n1 = name("list-a");
        String n2 = name("list-b");
        client.setSecret(n1, "v1");
        client.setSecret(n2, "v2");

        List<String> names = client.listPropertiesOfSecrets().stream()
                .map(SecretProperties::getName).toList();
        assertTrue(names.contains(n1));
        assertTrue(names.contains(n2));

        client.beginDeleteSecret(n1);
        client.beginDeleteSecret(n2);
    }

    @Test
    @DisplayName("list deleted secrets")
    void listDeletedSecrets() {
        String n = name("ldel");
        client.setSecret(n, "value");
        client.beginDeleteSecret(n);

        List<String> deletedNames = client.listDeletedSecrets().stream()
                .map(DeletedSecret::getName).toList();
        assertTrue(deletedNames.contains(n));
    }

    @Test
    @DisplayName("update secret properties")
    void updateSecretProperties() {
        String n = name("prop");
        client.setSecret(n, "value");
        String version = client.getSecret(n).getProperties().getVersion();

        SecretProperties props = client.getSecret(n).getProperties();
        props.setContentType("application/json");
        client.updateSecretProperties(props);

        assertEquals("application/json", client.getSecret(n).getProperties().getContentType());
        client.beginDeleteSecret(n);
    }

    @Test
    @DisplayName("disabled secret is not enabled")
    void disabledSecretAttribute() {
        String n = name("dis");
        KeyVaultSecret secret = new KeyVaultSecret(n, "hidden")
                .setProperties(new SecretProperties().setEnabled(false));
        client.setSecret(secret);
        // A disabled secret returns 403 on getSecret; verify the flag via the properties list.
        boolean foundEnabled = client.listPropertiesOfSecrets().stream()
                .filter(p -> p.getName().equals(n))
                .findFirst()
                .map(SecretProperties::isEnabled)
                .orElse(true);
        assertFalse(foundEnabled);
        client.beginDeleteSecret(n);
    }

    // -------------------------------------------------------------------------
    // Versioning
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("multiple versions are created")
    void multipleVersionsCreated() {
        String n = name("ver");
        client.setSecret(n, "v1");
        client.setSecret(n, "v2");
        client.setSecret(n, "v3");

        List<SecretProperties> versions = client.listPropertiesOfSecretVersions(n).stream().toList();
        assertEquals(3, versions.size());
        assertEquals("v3", client.getSecret(n).getValue());
        client.beginDeleteSecret(n);
    }

    @Test
    @DisplayName("get specific version")
    void getSpecificVersion() {
        String n = name("sv");
        client.setSecret(n, "first");
        String v1 = client.getSecret(n).getProperties().getVersion();
        client.setSecret(n, "second");

        assertEquals("first", client.getSecret(n, v1).getValue());
        assertEquals("second", client.getSecret(n).getValue());
        client.beginDeleteSecret(n);
    }

    @Test
    @DisplayName("version ID is embedded in secret URL")
    void versionIdInUrl() {
        String n = name("vid");
        client.setSecret(n, "value");
        String id = client.getSecret(n).getProperties().getId();
        String version = client.getSecret(n).getProperties().getVersion();
        assertTrue(id.contains(version), "Secret ID should contain version: " + id);
        client.beginDeleteSecret(n);
    }

    @Test
    @DisplayName("backup secret")
    void backupSecret() {
        String n = name("bak");
        client.setSecret(n, "backup-value");
        byte[] backup = client.backupSecret(n);
        assertNotNull(backup);
        assertTrue(backup.length > 0);
        client.beginDeleteSecret(n);
    }
}
