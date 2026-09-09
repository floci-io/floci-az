package io.floci.az.core.storage;

import io.floci.az.config.EmulatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves that a per-service storage override actually reaches the backend.
 *
 * <p>This is the test that fails when a service is missing its case in
 * {@code StorageFactory.serviceConfig}. Asserting the backend type under the default
 * configuration cannot catch that: {@code resolveMode} falls back to the global mode, so an
 * unwired service and a correctly wired one both yield the global backend and every assertion
 * passes either way. The override has to differ from the global mode for the wiring to be
 * observable at all, which is why this class carries a profile and
 * {@link StorageLadderWiringTest} does not.
 */
@QuarkusTest
@TestProfile(StorageLadderOverrideTest.EmailPersistentProfile.class)
@DisplayName("StorageFactory — a per-service mode override overrides only that service")
class StorageLadderOverrideTest {

    /**
     * Email is pinned to {@code persistent} while the global mode stays {@code memory}.
     * {@code persistent-path} is redirected to the build directory because the shipped default
     * is {@code ./data}, which would write into the working tree during a test run.
     */
    public static class EmailPersistentProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.storage.mode", "memory",
                "floci-az.storage.services.email.mode", "persistent",
                "floci-az.storage.persistent-path", Path.of("target", "storage-ladder-test").toString());
        }
    }

    @Inject
    StorageFactory storageFactory;

    @Inject
    EmulatorConfig config;

    @Test
    @DisplayName("email follows its own mode, not the global one")
    void emailOverrideTakesEffect() {
        assertEquals("memory", config.storage().mode(), "the global mode must differ for this test to prove anything");
        assertEquals(PersistentStorage.class, storageFactory.create("email").getClass());
    }

    @Test
    @DisplayName("the override does not leak into other services")
    void otherServicesKeepTheGlobalMode() {
        assertEquals(InMemoryStorage.class, storageFactory.create("queue").getClass());
        assertEquals(InMemoryStorage.class, storageFactory.create("blob").getClass());
    }
}
