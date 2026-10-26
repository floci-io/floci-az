package io.floci.az.services.entra;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.entra.EntraModels.RefreshToken;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefreshTokenStoreTest {
    private final InMemoryStorage<String, StoredObject> backend = new InMemoryStorage<>();
    private final StorageFactory factory = mock(StorageFactory.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);

    private EntraStore store() {
        when(factory.create("entra")).thenReturn(backend);
        when(config.services().entra().defaultTenantId()).thenReturn("tenant");
        return new EntraStore(factory, config);
    }

    private static RefreshToken grant() {
        return new RefreshToken("tenant", "tenant", "client", "user", "openid offline_access",
                false, false, Instant.now().plusSeconds(3600));
    }

    @Test
    void onlyDigestsArePersistedAndAnotherStoreCanReadTheGrant() {
        EntraStore first = store();
        RefreshToken grant = grant();
        String token = first.issueRefreshToken(grant);
        String other = first.issueRefreshToken(grant);
        assertNotEquals(token, other);
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        for (String key : backend.keys()) {
            assertFalse(key.contains(token));
            assertFalse(new String(backend.get(key).orElseThrow().data(), StandardCharsets.UTF_8).contains(token));
        }
        assertEquals(grant, store().getRefreshToken(token).orElseThrow());
    }

    @Test
    void revocationPreventsReplacementAfterAnEarlierLookup() {
        EntraStore store = store();
        String token = store.issueRefreshToken(grant());
        RefreshToken lookedUp = store.getRefreshToken(token).orElseThrow();
        store.revokeRefreshToken(token);
        assertTrue(store.getRefreshToken(token).isEmpty());
        assertTrue(store.renewRefreshToken(token, lookedUp, grant()).isEmpty());
    }

    @Test
    void resetRevokesRefreshTokensWithoutRemovingDirectoryObjects() {
        EntraStore store = store();
        GraphDirectoryStore directory = new GraphDirectoryStore(backend, "tenant");
        directory.put(GraphDirectoryStore.user("tenant", "user", "user@example.test", "User", null));
        String token = store.issueRefreshToken(grant());
        store.clear();
        assertTrue(store.getRefreshToken(token).isEmpty());
        assertTrue(directory.get("tenant", DirectoryEntitySet.USERS, "user").isPresent());
    }
}
