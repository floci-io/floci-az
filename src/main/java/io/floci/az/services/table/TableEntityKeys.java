package io.floci.az.services.table;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Storage keys for table entities. A key is {@code account/table/entityKey(pk, rk)}; table names are
 * alphanumeric, so the first two '/' always delimit account and table.
 */
final class TableEntityKeys {

    private static final Logger LOGGER = Logger.getLogger(TableEntityKeys.class);

    private TableEntityKeys() {}

    /**
     * Length-prefixing the PartitionKey keeps the encoding injective whatever the keys contain:
     * {@code ("a_b", "c")} and {@code ("a", "b_c")} map to {@code 3:a_b_c} and {@code 1:a_b_c}.
     */
    static String entityKey(String partitionKey, String rowKey) {
        return partitionKey.length() + ":" + partitionKey + "_" + rowKey;
    }

    /**
     * Re-keys entities persisted under the legacy {@code pk_rk} encoding. The canonical key is
     * recomputed from the stored entity's own PartitionKey and RowKey, so the pass is idempotent.
     * Entities that already collided under the legacy encoding were overwritten before this ran
     * and cannot be recovered.
     */
    static int migrateLegacyKeys(StorageBackend<String, StoredObject> store, String namespacePrefix,
                                 ObjectMapper objectMapper) {
        Map<String, StoredObject> puts = new LinkedHashMap<>();
        Set<String> deletes = new HashSet<>();
        for (String storeKey : store.keys()) {
            if (storeKey.startsWith(namespacePrefix)) {
                continue;
            }
            int accountEnd = storeKey.indexOf('/');
            int tableEnd = accountEnd < 0 ? -1 : storeKey.indexOf('/', accountEnd + 1);
            if (tableEnd < 0) {
                continue;
            }
            store.get(storeKey).ifPresent(stored -> {
                String key = canonicalEntityKey(storeKey, stored, objectMapper);
                if (key == null) {
                    return;
                }
                String canonicalStoreKey = storeKey.substring(0, tableEnd + 1) + key;
                if (!canonicalStoreKey.equals(storeKey)) {
                    puts.put(canonicalStoreKey, new StoredObject(key, stored.data(), stored.metadata(),
                            stored.lastModified(), stored.etag()));
                    deletes.add(storeKey);
                }
            });
        }
        if (!puts.isEmpty()) {
            store.applyBatch(puts, deletes);
            LOGGER.infov("Migrated {0} table entities to the collision-free storage key encoding", puts.size());
        }
        return puts.size();
    }

    private static String canonicalEntityKey(String storeKey, StoredObject stored, ObjectMapper objectMapper) {
        try {
            JsonNode entity = objectMapper.readTree(stored.data());
            JsonNode partitionKey = entity == null ? null : entity.get("PartitionKey");
            JsonNode rowKey = entity == null ? null : entity.get("RowKey");
            if (partitionKey == null || !partitionKey.isTextual() || rowKey == null || !rowKey.isTextual()) {
                LOGGER.warnv("Table entity {0} has no PartitionKey/RowKey, leaving its storage key unchanged", storeKey);
                return null;
            }
            return entityKey(partitionKey.asText(), rowKey.asText());
        } catch (IOException e) {
            LOGGER.warnv(e, "Table entity {0} is not valid JSON, leaving its storage key unchanged", storeKey);
            return null;
        }
    }
}
