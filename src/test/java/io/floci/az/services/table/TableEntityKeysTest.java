package io.floci.az.services.table;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableEntityKeysTest {

    private static final String NS_PREFIX = "__ns__:";
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

    private StoredObject entity(String partitionKey, String rowKey, String etag) {
        String json = "{\"PartitionKey\":\"" + partitionKey + "\",\"RowKey\":\"" + rowKey + "\",\"v\":1}";
        return new StoredObject(partitionKey + "_" + rowKey, json.getBytes(StandardCharsets.UTF_8),
                Map.of(), Instant.now(), etag);
    }
}
