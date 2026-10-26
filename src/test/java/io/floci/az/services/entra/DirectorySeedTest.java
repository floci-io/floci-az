package io.floci.az.services.entra;

import io.floci.az.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DirectorySeedTest {

    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"users\":{}}",
            "{\"user\":[]}",
            "{\"users\":[{\"id\":\"u\"}]}",
            "{\"users\":[{\"id\":\"u\",\"userPrincipalName\":\"a@b\"},{\"id\":\"u\",\"userPrincipalName\":\"b@b\"}]}",
            "{\"users\":[{\"id\":\"u\",\"userPrincipalName\":\"a@b\"},{\"id\":\"v\",\"userPrincipalName\":\"A@B\"}]}",
            "{\"users\":[{\"id\":\"u\",\"userPrincipalName\":\"a@b\"}],\"groups\":[{\"id\":\"g\",\"displayName\":\"Role\",\"members\":[\"missing\"]}]}"
    })
    void invalidSeedIsRejectedBeforeConfiguredObjectsAreWritten(String json) throws Exception {
        GraphDirectoryStore store = new GraphDirectoryStore(new InMemoryStorage<>(), "tenant");
        Path file = temp.resolve("seed.json");
        Files.writeString(file, json);
        assertThrows(IllegalArgumentException.class, () -> DirectorySeed.load(file, "tenant", store));
        for (DirectoryEntitySet set : DirectoryEntitySet.values()) {
            assertTrue(store.list("tenant", set).isEmpty());
        }
    }

    @Test
    void selectedTenantDoesNotWriteIntoTheDefaultDirectory() throws Exception {
        GraphDirectoryStore store = new GraphDirectoryStore(new InMemoryStorage<>(), "default");
        Path file = temp.resolve("seed.json");
        Files.writeString(file, """
                {"tenantId":"other","users":[{"id":"u","userPrincipalName":"user@example.test"}]}
                """);
        DirectorySeed.load(file, "default", store);
        assertTrue(store.get("default", DirectoryEntitySet.USERS, "u").isEmpty());
        assertTrue(store.get("other", DirectoryEntitySet.USERS, "u").isPresent());
    }

    @Test
    void parserErrorsDoNotExposeSeedSecrets() throws Exception {
        GraphDirectoryStore store = new GraphDirectoryStore(new InMemoryStorage<>(), "tenant");
        Path file = temp.resolve("seed.json");
        Files.writeString(file, "{\"clientSecret\":\"private-example-secret\",broken}");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DirectorySeed.load(file, "tenant", store));
        assertFalse(error.toString().contains("private-example-secret"));
        assertNull(error.getCause());
    }
}
