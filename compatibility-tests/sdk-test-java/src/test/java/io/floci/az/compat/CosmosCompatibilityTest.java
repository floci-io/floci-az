package io.floci.az.compat;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.*;
import com.azure.cosmos.models.CosmosBatch;
import com.azure.cosmos.models.CosmosBatchResponse;
import com.azure.cosmos.models.CosmosPatchOperations;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cosmos DB SDK compatibility tests — in-memory {@code cosmos} endpoint.
 *
 * <p>Covers: database / container / document lifecycle, upsert, PATCH, transactional batch,
 * and error-case assertions (404).  These tests run against the always-on in-memory handler
 * and require no Docker.</p>
 *
 * <p><b>SQL queries are not covered here.</b>  They require the VNext emulator engine
 * ({@code FLOCI_AZ_SERVICES_COSMOS_ENGINES_NOSQL_ENABLED=true}) and are tested in
 * {@link CosmosNoSqlEngineCompatibilityTest}.</p>
 *
 * <p>The azure-cosmos Java SDK enforces TLS in gateway mode regardless of the endpoint URL
 * scheme.  floci-az exposes HTTPS on port 4578 (with a self-signed certificate); certificate
 * validation is disabled via the SDK system property
 * {@code COSMOS.EMULATOR_SERVER_CERTIFICATE_VALIDATION_DISABLED=true}.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Cosmos DB Compatibility")
class CosmosCompatibilityTest {

    private CosmosClient client;

    @BeforeAll
    void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        client = EmulatorConfig.buildCosmosClient();
    }

    @AfterAll
    void teardown() {
        if (client != null) client.close();
    }

    private String dbId() {
        return "testdb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private Map<String, Object> doc(String id, String category, Object... extras) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("category", category);
        for (int i = 0; i < extras.length - 1; i += 2) {
            map.put(extras[i].toString(), extras[i + 1]);
        }
        return map;
    }

    // --- Golden path ---

    @Test
    @DisplayName("database lifecycle: create → list → delete")
    void databaseLifecycle() {
        String id = dbId();

        client.createDatabase(id);

        List<String> ids = client.readAllDatabases().stream()
            .map(CosmosDatabaseProperties::getId).toList();
        assertTrue(ids.contains(id));

        client.getDatabase(id).delete();

        List<String> after = client.readAllDatabases().stream()
            .map(CosmosDatabaseProperties::getId).toList();
        assertFalse(after.contains(id));
    }

    @Test
    @DisplayName("container lifecycle: create → list → delete")
    void containerLifecycle() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);

        db.createContainerIfNotExists("items", "/category");

        List<String> cids = db.readAllContainers().stream()
            .map(CosmosContainerProperties::getId).toList();
        assertTrue(cids.contains("items"));

        db.getContainer("items").delete();

        List<String> after = db.readAllContainers().stream()
            .map(CosmosContainerProperties::getId).toList();
        assertFalse(after.contains("items"));

        db.delete();
    }

    @Test
    @DisplayName("document lifecycle: create → read → replace → delete")
    @SuppressWarnings("unchecked")
    void documentCrud() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        // Create
        Map<String, Object> item = doc("laptop-1", "electronics", "name", "Laptop Pro", "price", 1299);
        CosmosItemResponse<Map> created = container.createItem(item, new PartitionKey("electronics"),
                new CosmosItemRequestOptions());
        assertEquals(201, created.getStatusCode());
        assertNotNull(created.getItem().get("_etag"));
        assertNotNull(created.getItem().get("_ts"));

        // Read
        Map<String, Object> read = container
            .readItem("laptop-1", new PartitionKey("electronics"), Map.class).getItem();
        assertEquals("Laptop Pro", read.get("name"));
        assertEquals(1299, ((Number) read.get("price")).intValue());

        // Replace
        read.put("price", 999);
        container.replaceItem(read, "laptop-1", new PartitionKey("electronics"),
                new CosmosItemRequestOptions());

        Map<String, Object> refreshed = container
            .readItem("laptop-1", new PartitionKey("electronics"), Map.class).getItem();
        assertEquals(999, ((Number) refreshed.get("price")).intValue());

        // Delete
        container.deleteItem("laptop-1", new PartitionKey("electronics"),
                new CosmosItemRequestOptions());

        CosmosException ex = assertThrows(CosmosException.class,
            () -> container.readItem("laptop-1", new PartitionKey("electronics"), Map.class));
        assertEquals(404, ex.getStatusCode());

        db.delete();
    }

    @Test
    @DisplayName("document upsert: create then overwrite")
    @SuppressWarnings("unchecked")
    void documentUpsert() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        container.upsertItem(doc("item-1", "tools", "stock", 10));
        Map<String, Object> v1 = container
            .readItem("item-1", new PartitionKey("tools"), Map.class).getItem();
        assertEquals(10, ((Number) v1.get("stock")).intValue());

        container.upsertItem(doc("item-1", "tools", "stock", 5));
        Map<String, Object> v2 = container
            .readItem("item-1", new PartitionKey("tools"), Map.class).getItem();
        assertEquals(5, ((Number) v2.get("stock")).intValue());

        db.delete();
    }

    @Test
    @DisplayName("database delete cascades to containers and documents")
    void cascadeDelete() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        db.getContainer("items").createItem(doc("orphan", "misc"));

        db.delete();

        List<String> after = client.readAllDatabases().stream()
            .map(CosmosDatabaseProperties::getId).toList();
        assertFalse(after.contains(id));
    }

    @Test
    @DisplayName("PATCH applies partial updates (add, set, replace, remove, incr)")
    @SuppressWarnings("unchecked")
    void patchDocument() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        container.createItem(doc("patch-1", "misc",
                "name", "Original", "counter", 10, "status", "draft", "removable", true));

        CosmosPatchOperations ops = CosmosPatchOperations.create()
                .add("/newField", "added")
                .set("/name", "Patched")
                .replace("/status", "active")
                .remove("/removable")
                .increment("/counter", 5L);

        Map<String, Object> patched = container
                .patchItem("patch-1", new PartitionKey("misc"), ops, Map.class)
                .getItem();

        assertEquals("added",   patched.get("newField"));
        assertEquals("Patched", patched.get("name"));
        assertEquals("active",  patched.get("status"));
        assertFalse(patched.containsKey("removable"));
        assertEquals(15, ((Number) patched.get("counter")).intValue());

        db.delete();
    }

    @Test
    @DisplayName("transactional batch: Create/Read/Replace/Delete/Upsert")
    @SuppressWarnings("unchecked")
    void transactionalBatch() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        // Batch 1: Create × 2 + Upsert
        CosmosBatch batch1 = CosmosBatch.createCosmosBatch(new PartitionKey("test"));
        batch1.createItemOperation(doc("b1", "test", "v", 1));
        batch1.createItemOperation(doc("b2", "test", "v", 2));
        batch1.upsertItemOperation(doc("b3", "test", "v", 3));

        CosmosBatchResponse r1 = container.executeCosmosBatch(batch1);
        assertEquals(200, r1.getStatusCode());
        assertEquals(201, r1.getResults().get(0).getStatusCode());
        assertEquals(201, r1.getResults().get(1).getStatusCode());
        assertTrue(r1.getResults().get(2).getStatusCode() >= 200);

        assertEquals(1, ((Number) container
                .readItem("b1", new PartitionKey("test"), Map.class)
                .getItem().get("v")).intValue());

        // Batch 2: Read + Replace + Delete
        CosmosBatch batch2 = CosmosBatch.createCosmosBatch(new PartitionKey("test"));
        batch2.readItemOperation("b1");
        batch2.replaceItemOperation("b2", doc("b2", "test", "v", 99));
        batch2.deleteItemOperation("b3");

        CosmosBatchResponse r2 = container.executeCosmosBatch(batch2);
        assertEquals(200, r2.getStatusCode());
        assertEquals(200, r2.getResults().get(0).getStatusCode());  // read
        assertEquals(200, r2.getResults().get(1).getStatusCode());  // replace
        assertEquals(204, r2.getResults().get(2).getStatusCode());  // delete

        assertEquals(99, ((Number) container
                .readItem("b2", new PartitionKey("test"), Map.class)
                .getItem().get("v")).intValue());
        assertThrows(CosmosException.class,
                () -> container.readItem("b3", new PartitionKey("test"), Map.class));

        db.delete();
    }

    // --- Error cases ---

    @Test
    @DisplayName("read missing document → CosmosException (404)")
    void documentNotFound() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        CosmosException ex = assertThrows(CosmosException.class,
            () -> container.readItem("no-such-doc", new PartitionKey("misc"), Map.class));
        assertEquals(404, ex.getStatusCode());

        db.delete();
    }

    @Test
    @DisplayName("read missing database → CosmosException (404)")
    void databaseNotFound() {
        CosmosException ex = assertThrows(CosmosException.class,
            () -> client.getDatabase("no-such-db-xyz").read());
        assertEquals(404, ex.getStatusCode());
    }

    // --- Time to live ---

    @Test
    @DisplayName("container TTL: defaultTtl round-trips, expired items disappear, ttl=-1 opts out")
    void containerTtl() throws InterruptedException {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);

        CosmosContainerProperties props = new CosmosContainerProperties("events", "/category");
        props.setDefaultTimeToLiveInSeconds(2);
        db.createContainer(props);
        CosmosContainer container = db.getContainer("events");

        assertEquals(Integer.valueOf(2),
            container.read().getProperties().getDefaultTimeToLiveInSeconds());

        container.createItem(doc("short-lived", "logs"), new PartitionKey("logs"),
                new CosmosItemRequestOptions());
        container.createItem(doc("keeper", "logs", "ttl", -1), new PartitionKey("logs"),
                new CosmosItemRequestOptions());

        // the item using the container default expires within the TTL window
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            try {
                container.readItem("short-lived", new PartitionKey("logs"), Map.class);
                if (System.currentTimeMillis() > deadline) fail("item did not expire within 10s");
                Thread.sleep(250);
            } catch (CosmosException e) {
                assertEquals(404, e.getStatusCode());
                break;
            }
        }

        // the ttl=-1 item outlives the container default
        assertEquals("keeper",
            container.readItem("keeper", new PartitionKey("logs"), Map.class).getItem().get("id"));

        // replacing the container without a TTL switches it off
        CosmosContainerProperties replace = container.read().getProperties();
        replace.setDefaultTimeToLiveInSeconds(null);
        container.replace(replace);
        assertNull(container.read().getProperties().getDefaultTimeToLiveInSeconds());

        db.delete();
    }
}
