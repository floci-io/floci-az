package io.floci.az.compat;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.*;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.azure.core.util.paging.ContinuablePage;
import com.azure.cosmos.models.FeedResponse;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cosmos DB SDK compatibility tests.
 *
 * <p>The azure-cosmos Java SDK enforces TLS in gateway mode regardless of the
 * endpoint URL scheme.  floci-az exposes HTTPS on port 4578 (with a self-signed
 * certificate); certificate validation is disabled via the SDK system property
 * {@code COSMOS.EMULATOR_SERVER_CERTIFICATE_VALIDATION_DISABLED=true}.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Cosmos DB Compatibility")
class CosmosCompatibilityTest {

    private CosmosClient client;

    @BeforeAll
    void setup() {
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
    @DisplayName("document list: create 3 → readAll → count matches")
    @SuppressWarnings("unchecked")
    void documentList() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        for (int i = 0; i < 3; i++) {
            container.createItem(doc("item-" + i, "books", "title", "Book " + i));
        }

        CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();
        List<Map> items = container.queryItems("SELECT * FROM c", opts, Map.class).stream().toList();
        assertEquals(3, items.size());

        Set<String> ids = new HashSet<>();
        items.forEach(it -> ids.add((String) it.get("id")));
        assertTrue(ids.containsAll(Set.of("item-0", "item-1", "item-2")));

        db.delete();
    }

    @Test
    @DisplayName("query WHERE with named parameter filters documents")
    @SuppressWarnings("unchecked")
    void queryWhere() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        container.createItem(doc("cheap", "misc", "price", 10));
        container.createItem(doc("expensive", "misc", "price", 500));

        SqlQuerySpec spec = new SqlQuerySpec(
            "SELECT * FROM c WHERE c.price > @minPrice",
            Collections.singletonList(new SqlParameter("@minPrice", 100)));

        List<Map> results = container
            .queryItems(spec, new CosmosQueryRequestOptions(), Map.class)
            .stream().toList();

        assertEquals(1, results.size());
        assertEquals("expensive", results.get(0).get("id"));

        db.delete();
    }

    @Test
    @DisplayName("query ORDER BY ASC sorts documents")
    @SuppressWarnings("unchecked")
    void queryOrderBy() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        container.createItem(doc("c", "sort", "rank", 3));
        container.createItem(doc("a", "sort", "rank", 1));
        container.createItem(doc("b", "sort", "rank", 2));

        List<Map> results = container
            .queryItems("SELECT * FROM c ORDER BY c.rank ASC",
                new CosmosQueryRequestOptions(), Map.class)
            .stream().toList();

        List<Integer> ranks = results.stream()
            .map(r -> ((Number) r.get("rank")).intValue()).toList();
        assertEquals(List.of(1, 2, 3), ranks);

        db.delete();
    }

    @Test
    @DisplayName("query SELECT VALUE COUNT(1) returns total count")
    void queryCount() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        for (int i = 0; i < 4; i++) {
            container.createItem(doc("item-" + i, "count-test"));
        }

        CosmosPagedIterable<Object> results = container
            .queryItems("SELECT VALUE COUNT(1) FROM c",
                new CosmosQueryRequestOptions(), Object.class);

        Object count = results.iterator().next();
        assertEquals(4, ((Number) count).intValue());

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
    @DisplayName("pagination: x-ms-max-item-count splits results into pages")
    @SuppressWarnings("unchecked")
    void pagination() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        int total = 10;
        for (int i = 0; i < total; i++) {
            container.createItem(doc(String.format("item-%02d", i), "page-test", "rank", i));
        }

        int pageSize = 3;
        List<String> allIds = new ArrayList<>();
        int pageCount = 0;

        Iterable<FeedResponse<Map>> pages = container
                .queryItems("SELECT * FROM c", new CosmosQueryRequestOptions(), Map.class)
                .iterableByPage(pageSize);

        for (FeedResponse<Map> page : pages) {
            List<Map> items = page.getResults();
            assertTrue(items.size() <= pageSize,
                    "Page " + pageCount + " has " + items.size() + " items, expected <= " + pageSize);
            items.forEach(item -> allIds.add((String) item.get("id")));
            pageCount++;
        }

        assertTrue(pageCount >= 2, "Expected at least 2 pages, got " + pageCount);
        assertEquals(total, allIds.size());
        for (int i = 0; i < total; i++) {
            assertTrue(allIds.contains(String.format("item-%02d", i)));
        }

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
}
