package io.floci.az.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageBatchTest {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    @TempDir
    Path tempDir;

    @Test
    void persistentStorageWritesOneBatchSnapshot() {
        Path file = tempDir.resolve("persistent.json");
        PersistentStorage<String, String> storage = new PersistentStorage<>(file, STRING_MAP);
        storage.put("old", "value");

        storage.applyBatch(Map.of("new", "value"), Set.of("old"));

        PersistentStorage<String, String> recovered = new PersistentStorage<>(file, STRING_MAP);
        recovered.load();
        assertBatchApplied(recovered);
    }

    @Test
    void hybridStorageFlushesOneBatchSnapshot() {
        Path file = tempDir.resolve("hybrid.json");
        HybridStorage<String, String> storage = new HybridStorage<>(file, STRING_MAP, 60_000);
        try {
            storage.put("old", "value");
            storage.applyBatch(Map.of("new", "value"), Set.of("old"));
            storage.flush();

            HybridStorage<String, String> recovered =
                    new HybridStorage<>(file, STRING_MAP, 60_000);
            try {
                recovered.load();
                assertBatchApplied(recovered);
            } finally {
                recovered.shutdown();
            }
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void walStorageWritesBatchAsOneEntry() throws IOException {
        Path snapshot = tempDir.resolve("snapshot.json");
        Path wal = tempDir.resolve("storage.wal");
        WalStorage<String, String> storage =
                new WalStorage<>(snapshot, wal, STRING_MAP, 60_000);
        try {
            storage.load();
            storage.applyBatch(Map.of("new", "value"), Set.of("old"));

            assertBatchApplied(storage);
            try (DataInputStream input = new DataInputStream(Files.newInputStream(wal))) {
                assertEquals(WalStorage.OP_BATCH, input.readByte());
                int payloadLength = input.readInt();
                assertEquals(payloadLength, input.readAllBytes().length);
            }
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void walStorageIgnoresIncompleteBatchEntry() throws IOException {
        Path snapshot = tempDir.resolve("incomplete-snapshot.json");
        Path wal = tempDir.resolve("incomplete-storage.wal");
        ByteBuffer incompleteEntry = ByteBuffer.allocate(7)
                .put(WalStorage.OP_BATCH)
                .putInt(10)
                .putShort((short) 1);
        Files.write(wal, incompleteEntry.array());
        WalStorage<String, String> storage =
                new WalStorage<>(snapshot, wal, STRING_MAP, 60_000);
        try {
            storage.load();
            assertTrue(storage.keys().isEmpty());
        } finally {
            storage.shutdown();
        }
    }

    private static void assertBatchApplied(StorageBackend<String, String> storage) {
        assertFalse(storage.get("old").isPresent());
        assertEquals("value", storage.get("new").orElseThrow());
    }
}
