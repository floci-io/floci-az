package io.floci.az.services.table;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Storage keys for table entities. A key is {@code account/table/entityKey(pk, rk)}; table names are
 * alphanumeric, so the first two '/' always delimit account and table. The table segment is lowercase: Azure table
 * names are case-insensitive, so every casing of a name reaches one table.
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

    /** The table segment every key uses: table names are ASCII letters and digits, so this is total. */
    static String tableSegment(String tableName) {
        return tableName.toLowerCase(Locale.ROOT);
    }

    /**
     * Moves every table whose stored segment is not lowercase to the lowercase one: its namespace record, which keeps
     * the spelling it was stored under as its key (the name Query Tables lists), and its entities. Two tables that
     * differ only in case could both be created before names matched case-insensitively. Of those, the one already at
     * the lowercase segment wins, or else the lexicographically first spelling; the others stay where they are,
     * unreachable but not deleted, with a warning naming them. Idempotent.
     *
     * @return the number of tables moved
     */
    static int migrateTableNameCase(StorageBackend<String, StoredObject> store, String namespacePrefix) {
        // account -> lowercase segment -> stored spellings
        Map<String, Map<String, List<String>>> spellings = new HashMap<>();
        for (String storeKey : store.keys()) {
            if (!storeKey.startsWith(namespacePrefix)) {
                continue;
            }
            String rest = storeKey.substring(namespacePrefix.length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String table = rest.substring(slash + 1);
            spellings.computeIfAbsent(rest.substring(0, slash), a -> new TreeMap<>())
                    .computeIfAbsent(tableSegment(table), t -> new ArrayList<>())
                    .add(table);
        }

        Map<String, String> moves = new HashMap<>();   // "account/Spelling/" -> "account/spelling/"
        Map<String, StoredObject> puts = new LinkedHashMap<>();
        Set<String> deletes = new HashSet<>();
        spellings.forEach((account, tables) -> tables.forEach((segment, names) -> {
            String winner = names.contains(segment)
                    ? segment : names.stream().min(Comparator.naturalOrder()).orElseThrow();
            if (names.size() > 1) {
                LOGGER.warnv("Tables {0} of account {1} differ only in case; {2} is kept, the others stay stored "
                        + "but unreachable", names, account, winner);
            }
            if (winner.equals(segment)) {
                return;
            }
            String oldKey = namespacePrefix + account + "/" + winner;
            store.get(oldKey).ifPresent(record -> {
                String spelling = record.key() == null || record.key().isEmpty() ? winner : record.key();
                puts.put(namespacePrefix + account + "/" + segment, new StoredObject(spelling, record.data(),
                        record.metadata(), record.lastModified(), record.etag()));
                deletes.add(oldKey);
                moves.put(account + "/" + winner + "/", account + "/" + segment + "/");
            });
        }));
        if (moves.isEmpty()) {
            return 0;
        }
        for (String storeKey : store.keys()) {
            if (storeKey.startsWith(namespacePrefix)) {
                continue;
            }
            int accountEnd = storeKey.indexOf('/');
            int tableEnd = accountEnd < 0 ? -1 : storeKey.indexOf('/', accountEnd + 1);
            if (tableEnd < 0) {
                continue;
            }
            String target = moves.get(storeKey.substring(0, tableEnd + 1));
            if (target != null) {
                store.get(storeKey).ifPresent(entity -> {
                    puts.put(target + storeKey.substring(tableEnd + 1), entity);
                    deletes.add(storeKey);
                });
            }
        }
        store.applyBatch(puts, deletes);
        LOGGER.infov("Moved {0} tables to case-insensitive storage keys", moves.size());
        return moves.size();
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
