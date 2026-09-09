package io.floci.az.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clearing a backend must survive a restart in every persistent mode.
 *
 * <p>{@code /_admin/reset} calls {@code clear()} on each service and returns; nothing flushes
 * afterwards. If a mode defers its write, the emulator can be stopped between the reset and the
 * next scheduler tick, and the state the reset was meant to erase comes back on the next load.
 * For a reset endpoint whose whole purpose is test isolation, that is the difference between a
 * clean run and one contaminated by the previous suite.
 *
 * <p>Each case uses a 60 s flush interval so a passing result cannot come from the background
 * scheduler rescuing the write.
 */
@DisplayName("StorageBackend — clear() is durable without waiting for a flush")
class StorageClearDurabilityTest {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("hybrid: cleared state is on disk before clear() returns")
    void hybridClearIsDurable() {
        Path file = tempDir.resolve("hybrid.json");
        HybridStorage<String, String> storage = new HybridStorage<>(file, STRING_MAP, 60_000);
        try {
            storage.put("captured", "value");
            storage.flush();

            storage.clear();

            assertReloadsEmpty(new HybridStorage<>(file, STRING_MAP, 60_000));
        } finally {
            storage.shutdown();
        }
    }

    @Test
    @DisplayName("persistent: cleared state is on disk before clear() returns")
    void persistentClearIsDurable() {
        Path file = tempDir.resolve("persistent.json");
        PersistentStorage<String, String> storage = new PersistentStorage<>(file, STRING_MAP);
        storage.put("captured", "value");

        storage.clear();

        PersistentStorage<String, String> recovered = new PersistentStorage<>(file, STRING_MAP);
        recovered.load();
        assertTrue(recovered.keys().isEmpty(), "persistent storage restored cleared state: " + recovered.keys());
    }

    private void assertReloadsEmpty(HybridStorage<String, String> recovered) {
        try {
            recovered.load();
            assertTrue(recovered.keys().isEmpty(),
                "clear() left the pre-reset state on disk, so a restart before the next flush restores it: "
                    + recovered.keys());
        } finally {
            recovered.shutdown();
        }
    }
}
