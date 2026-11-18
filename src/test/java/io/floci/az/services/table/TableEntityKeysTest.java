package io.floci.az.services.table;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.HybridStorage;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.PersistentStorage;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.WalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableEntityKeysTest {

    private static final String NS_PREFIX = "__ns__:";
    private static final TypeReference<Map<String, StoredObject>> TYPE_REF = new TypeReference<>() {};
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keysContainingTheSeparatorDoNotCollide() {
        assertNotEquals(TableEntityKeys.entityKey("a_b", "c"), TableEntityKeys.entityKey("a", "b_c"));
        assertNotEquals(TableEntityKeys.entityKey("", "a_b"), TableEntityKeys.entityKey("a", "b"));
        assertNotEquals(TableEntityKeys.entityKey("1:a", "b"), TableEntityKeys.entityKey("1", "a_b"));
    }

    @Test
    void migrationRekeysLegacyEntitiesAndKeepsTheirContent() {
        InMemoryStorage<String, StoredObject> store = new InMemoryStorage<>();
        store.put(NS_PREFIX + "acct/Orders", new StoredObject("", new byte[0], Map.of(), Instant.EPOCH, ""));
        StoredObject legacy = entity("a_b", "c", "etag-1");
        store.put("acct/Orders/a_b_c", legacy);

        assertEquals(1, TableEntityKeys.migrateLegacyKeys(store, NS_PREFIX, objectMapper));

        String migratedKey = "acct/Orders/" + TableEntityKeys.entityKey("a_b", "c");
        StoredObject migrated = store.get(migratedKey).orElseThrow();
        assertEquals("etag-1", migrated.etag());
        assertEquals(TableEntityKeys.entityKey("a_b", "c"), migrated.key());
        assertArrayEquals(legacy.data(), migrated.data());
        assertTrue(store.get("acct/Orders/a_b_c").isEmpty());
        assertTrue(store.get(NS_PREFIX + "acct/Orders").isPresent());
    }

    @Test
    void migrationIsIdempotent() {
        InMemoryStorage<String, StoredObject> store = new InMemoryStorage<>();
        store.put("acct/Orders/p_r", entity("p", "r", "etag-1"));

        assertEquals(1, TableEntityKeys.migrateLegacyKeys(store, NS_PREFIX, objectMapper));
        assertEquals(0, TableEntityKeys.migrateLegacyKeys(store, NS_PREFIX, objectMapper));
        assertEquals(1, store.keys().size());
    }

    @Test
    void migrationLeavesUnparseableEntitiesInPlace() {
        InMemoryStorage<String, StoredObject> store = new InMemoryStorage<>();
        StoredObject broken = new StoredObject("x", "not json".getBytes(StandardCharsets.UTF_8),
                Map.of(), Instant.now(), "etag");
        store.put("acct/Orders/x", broken);

        assertEquals(0, TableEntityKeys.migrateLegacyKeys(store, NS_PREFIX, objectMapper));
        assertTrue(store.get("acct/Orders/x").isPresent());
    }

    @Test
    void migrationLowercasesTableSegmentsAndKeepsTheSpelling() {
        InMemoryStorage<String, StoredObject> store = new InMemoryStorage<>();
        store.put(NS_PREFIX + "acct/Orders", namespaceRecord(""));
        store.put("acct/Orders/" + TableEntityKeys.entityKey("p", "r"), entity("p", "r", "etag-1"));
        store.put(NS_PREFIX + "acct/lower", namespaceRecord(""));

        assertEquals(1, TableEntityKeys.migrateTableNameCase(store, NS_PREFIX));

        assertEquals("Orders", store.get(NS_PREFIX + "acct/orders").orElseThrow().key());
        assertEquals("etag-1",
                store.get("acct/orders/" + TableEntityKeys.entityKey("p", "r")).orElseThrow().etag());
        assertTrue(store.get(NS_PREFIX + "acct/Orders").isEmpty());
        assertTrue(store.get("acct/Orders/" + TableEntityKeys.entityKey("p", "r")).isEmpty());
        assertTrue(store.get(NS_PREFIX + "acct/lower").isPresent());
        assertEquals(3, store.keys().size());
    }

    @Test
    void tableNameMigrationIsIdempotent() {
        InMemoryStorage<String, StoredObject> store = new InMemoryStorage<>();
        store.put(NS_PREFIX + "acct/Orders", namespaceRecord(""));

        assertEquals(1, TableEntityKeys.migrateTableNameCase(store, NS_PREFIX));
        assertEquals(0, TableEntityKeys.migrateTableNameCase(store, NS_PREFIX));
        assertEquals("Orders", store.get(NS_PREFIX + "acct/orders").orElseThrow().key());
    }

    @Test
    void migrationLeavesTheLoserOfACaseCollisionInPlace() {
        InMemoryStorage<String, StoredObject> store = new InMemoryStorage<>();
        store.put(NS_PREFIX + "acct/MyTable", namespaceRecord(""));
        store.put("acct/MyTable/" + TableEntityKeys.entityKey("p", "a"), entity("p", "a", "etag-a"));
        store.put(NS_PREFIX + "acct/MYTABLE", namespaceRecord(""));
        store.put("acct/MYTABLE/" + TableEntityKeys.entityKey("p", "b"), entity("p", "b", "etag-b"));

        assertEquals(1, TableEntityKeys.migrateTableNameCase(store, NS_PREFIX));

        // "MYTABLE" sorts before "MyTable", so it wins; MyTable's records stay where they were.
        assertEquals("MYTABLE", store.get(NS_PREFIX + "acct/mytable").orElseThrow().key());
        assertEquals("etag-b",
                store.get("acct/mytable/" + TableEntityKeys.entityKey("p", "b")).orElseThrow().etag());
        assertTrue(store.get(NS_PREFIX + "acct/MyTable").isPresent());
        assertTrue(store.get("acct/MyTable/" + TableEntityKeys.entityKey("p", "a")).isPresent());
        assertTrue(store.get("acct/mytable/" + TableEntityKeys.entityKey("p", "a")).isEmpty());
    }

    @Test
    void aTableAlreadyAtTheLowercaseSegmentWinsACaseCollision() {
        InMemoryStorage<String, StoredObject> store = new InMemoryStorage<>();
        store.put(NS_PREFIX + "acct/mytable", namespaceRecord(""));
        store.put(NS_PREFIX + "acct/MyTable", namespaceRecord(""));

        assertEquals(0, TableEntityKeys.migrateTableNameCase(store, NS_PREFIX));
        assertTrue(store.get(NS_PREFIX + "acct/MyTable").isPresent());
        assertEquals(2, store.keys().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void tablesMovedToLowercaseSurviveARestartOfDurableStorage(String mode, @TempDir Path dir) {
        StorageBackend<String, StoredObject> beforeUpgrade = open(mode, dir);
        beforeUpgrade.put(NS_PREFIX + "acct/Orders", namespaceRecord(""));
        beforeUpgrade.put("acct/Orders/" + TableEntityKeys.entityKey("p", "r"), entity("p", "r", "etag-1"));
        close(beforeUpgrade);

        StorageBackend<String, StoredObject> upgraded = open(mode, dir);
        assertEquals(1, TableEntityKeys.migrateTableNameCase(upgraded, NS_PREFIX));
        close(upgraded);

        StorageBackend<String, StoredObject> restarted = open(mode, dir);
        assertEquals(0, TableEntityKeys.migrateTableNameCase(restarted, NS_PREFIX));
        assertEquals("Orders", restarted.get(NS_PREFIX + "acct/orders").orElseThrow().key());
        assertEquals("etag-1",
                restarted.get("acct/orders/" + TableEntityKeys.entityKey("p", "r")).orElseThrow().etag());
        assertEquals(2, restarted.keys().size());
        close(restarted);
    }

    private static StoredObject namespaceRecord(String spelling) {
        return new StoredObject(spelling, new byte[0], Map.of(), Instant.EPOCH, "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void migratedEntitiesSurviveARestartOfDurableStorage(String mode, @TempDir Path dir) {
        StorageBackend<String, StoredObject> beforeUpgrade = open(mode, dir);
        beforeUpgrade.put(NS_PREFIX + "acct/Orders", new StoredObject("", new byte[0], Map.of(), Instant.EPOCH, ""));
        beforeUpgrade.put("acct/Orders/a_b_c", entity("a_b", "c", "etag-1"));
        beforeUpgrade.put("acct/Orders/a_b_c2", entity("a", "b_c2", "etag-2"));
        close(beforeUpgrade);

        StorageBackend<String, StoredObject> upgraded = open(mode, dir);
        assertEquals(2, TableEntityKeys.migrateLegacyKeys(upgraded, NS_PREFIX, objectMapper));
        close(upgraded);

        StorageBackend<String, StoredObject> restarted = open(mode, dir);
        assertEquals(0, TableEntityKeys.migrateLegacyKeys(restarted, NS_PREFIX, objectMapper));
        assertEquals("etag-1",
                restarted.get("acct/Orders/" + TableEntityKeys.entityKey("a_b", "c")).orElseThrow().etag());
        assertEquals("etag-2",
                restarted.get("acct/Orders/" + TableEntityKeys.entityKey("a", "b_c2")).orElseThrow().etag());
        assertTrue(restarted.get("acct/Orders/a_b_c").isEmpty());
        assertTrue(restarted.get("acct/Orders/a_b_c2").isEmpty());
        assertTrue(restarted.get(NS_PREFIX + "acct/Orders").isPresent());
        assertEquals(3, restarted.keys().size());
        close(restarted);
    }

    private static StorageBackend<String, StoredObject> open(String mode, Path dir) {
        StorageBackend<String, StoredObject> backend = switch (mode) {
            case "persistent" -> new PersistentStorage<>(dir.resolve("table.json"), TYPE_REF);
            case "hybrid" -> new HybridStorage<>(dir.resolve("table.json"), TYPE_REF, 60_000);
            case "wal" -> new WalStorage<>(dir.resolve("table-snapshot.json"), dir.resolve("table.wal"),
                    TYPE_REF, 60_000);
            default -> throw new IllegalArgumentException(mode);
        };
        backend.load();
        return backend;
    }

    private static void close(StorageBackend<String, StoredObject> backend) {
        switch (backend) {
            case HybridStorage<String, StoredObject> hybrid -> hybrid.shutdown();
            case WalStorage<String, StoredObject> wal -> wal.shutdown();
            default -> backend.flush();
        }
    }

    private StoredObject entity(String partitionKey, String rowKey, String etag) {
        String json = "{\"PartitionKey\":\"" + partitionKey + "\",\"RowKey\":\"" + rowKey + "\",\"v\":1}";
        return new StoredObject(partitionKey + "_" + rowKey, json.getBytes(StandardCharsets.UTF_8),
                Map.of(), Instant.now(), etag);
    }
}
