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

    // --- Indexing policy & composite indexes (#127) ---
    //
    // These queries run against the in-memory handler (unlike the general SQL
    // coverage in CosmosNoSqlEngineCompatibilityTest) because the composite-index
    // ORDER BY validation is implemented handler-side.

    private CosmosContainerProperties chatContainerProperties(boolean withCompositeIndex) {
        CosmosContainerProperties props = new CosmosContainerProperties("chat", "/conversationId");
        if (withCompositeIndex) {
            props.setIndexingPolicy(compositeIndexPolicy());
        }
        return props;
    }

    private IndexingPolicy compositeIndexPolicy() {
        IndexingPolicy policy = new IndexingPolicy();
        policy.setIncludedPaths(List.of(new IncludedPath("/*")));
        policy.setExcludedPaths(List.of(new ExcludedPath("/\"_etag\"/?")));
        CompositePath conversationId = new CompositePath()
            .setPath("/conversationId").setOrder(CompositePathSortOrder.ASCENDING);
        CompositePath sequence = new CompositePath()
            .setPath("/sequence").setOrder(CompositePathSortOrder.ASCENDING);
        policy.setCompositeIndexes(List.of(List.of(conversationId, sequence)));
        return policy;
    }

    private void insertMessage(CosmosContainer container, String id, String conversationId, int sequence) {
        container.createItem(
            doc(id, "chat", "conversationId", conversationId, "sequence", sequence),
            new PartitionKey(conversationId), null);
    }

    @Test
    @DisplayName("container create: custom indexing policy with composite indexes round-trips")
    void containerIndexingPolicyRoundTrip() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);

        db.createContainer(chatContainerProperties(true));

        CosmosContainerProperties read = db.getContainer("chat").read().getProperties();
        List<List<CompositePath>> composites = read.getIndexingPolicy().getCompositeIndexes();
        assertEquals(1, composites.size());
        assertEquals("/conversationId", composites.get(0).get(0).getPath());
        assertEquals("/sequence", composites.get(0).get(1).getPath());
        assertEquals(CompositePathSortOrder.ASCENDING, composites.get(0).get(0).getOrder());

        db.delete();
    }

    @Test
    @DisplayName("container replace: adding a composite index persists")
    void replaceContainerIndexingPolicy() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainer(chatContainerProperties(false));
        CosmosContainer container = db.getContainer("chat");

        CosmosContainerProperties props = container.read().getProperties();
        props.setIndexingPolicy(compositeIndexPolicy());
        container.replace(props);

        List<List<CompositePath>> composites =
            container.read().getProperties().getIndexingPolicy().getCompositeIndexes();
        assertEquals(1, composites.size());
        assertEquals("/conversationId", composites.get(0).get(0).getPath());

        db.delete();
    }

    @Test
    @DisplayName("multi-property ORDER BY without composite index → CosmosException (400)")
    void multiPropertyOrderByWithoutCompositeIndexFails() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainer(chatContainerProperties(false));
        CosmosContainer container = db.getContainer("chat");
        insertMessage(container, "m1", "conv1", 2);
        insertMessage(container, "m2", "conv1", 1);

        CosmosException ex = assertThrows(CosmosException.class, () ->
            container.queryItems(
                "SELECT * FROM c ORDER BY c.conversationId, c.sequence",
                new CosmosQueryRequestOptions(), Map.class)
                .stream().toList());
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("composite index"),
            "expected composite-index error, got: " + ex.getMessage());

        db.delete();
    }

    @Test
    @DisplayName("container create with single-path composite index → CosmosException (400)")
    void singlePathCompositeIndexRejected() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);

        CosmosContainerProperties props = new CosmosContainerProperties("bad", "/conversationId");
        IndexingPolicy policy = new IndexingPolicy();
        policy.setCompositeIndexes(List.of(List.of(
            new CompositePath().setPath("/a").setOrder(CompositePathSortOrder.ASCENDING))));
        props.setIndexingPolicy(policy);

        CosmosException ex = assertThrows(CosmosException.class, () -> db.createContainer(props));
        assertEquals(400, ex.getStatusCode());

        db.delete();
    }

    @Test
    @DisplayName("multi-property ORDER BY with matching composite index succeeds")
    void multiPropertyOrderByWithCompositeIndexSucceeds() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainer(chatContainerProperties(true));
        CosmosContainer container = db.getContainer("chat");
        insertMessage(container, "m1", "conv1", 2);
        insertMessage(container, "m2", "conv1", 1);
        insertMessage(container, "m3", "conv0", 5);

        List<String> ids = container.queryItems(
                "SELECT * FROM c ORDER BY c.conversationId, c.sequence",
                new CosmosQueryRequestOptions(), Map.class)
            .stream().map(m -> String.valueOf(m.get("id"))).toList();

        assertEquals(List.of("m3", "m2", "m1"), ids);

        db.delete();
    }
}
