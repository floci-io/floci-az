package io.floci.az.core.storage;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers email's default-configuration behaviour: the accessor exists, the factory hands back a
 * backend, and repeat calls reuse it.
 *
 * <p>These assertions deliberately do <em>not</em> prove the per-service override is wired up.
 * Under the shipped configuration the global mode and the email mode are the same, so an unwired
 * service is indistinguishable from a wired one here. {@link StorageLadderOverrideTest} is the
 * test that pins that, by setting an email mode that differs from the global one.
 */
@QuarkusTest
@DisplayName("StorageFactory — per-service ladder wiring")
class StorageLadderWiringTest {

    @Inject
    StorageFactory storageFactory;

    @Inject
    EmulatorConfig config;

    @Test
    @DisplayName("email resolves its own ServiceStorageConfig, not just the global mode")
    void emailIsWiredIntoTheLadder() {
        assertNotNull(config.storage().services().email(),
                "storage.services.email must exist for the per-service override to be reachable");

        StorageBackend<String, StoredObject> backend = storageFactory.create("email");
        assertNotNull(backend);

        // The factory caches per service name; a second call must not create a second backend,
        // or a flush on shutdown would write from a store nobody has been reading.
        assertSame(backend, storageFactory.create("email"));
    }

    @Test
    @DisplayName("the default configuration puts email on the global memory mode")
    void emailFollowsTheGlobalModeByDefault() {
        assertEquals("memory", config.storage().mode());
        assertEquals(InMemoryStorage.class, storageFactory.create("email").getClass());
    }
}
