package io.floci.az.compat;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.*;
import com.azure.cosmos.models.CosmosPatchOperations;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.azure.core.util.paging.ContinuablePage;
import com.azure.cosmos.models.FeedResponse;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.HashMap;

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
    @DisplayName("aggregate SUM/AVG/MIN/MAX return correct scalar values")
    void aggregateSumAvgMinMax() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        int[] prices = {10, 20, 30, 40}; // sum=100, avg=25, min=10, max=40
        for (int i = 0; i < prices.length; i++) {
            container.createItem(doc("item-" + i, "agg", "price", prices[i]));
        }

        Object sum = container.queryItems("SELECT VALUE SUM(c.price) FROM c",
                new CosmosQueryRequestOptions(), Object.class).iterator().next();
        assertEquals(100, ((Number) sum).intValue());

        Object avg = container.queryItems("SELECT VALUE AVG(c.price) FROM c",
                new CosmosQueryRequestOptions(), Object.class).iterator().next();
        assertEquals(25.0, ((Number) avg).doubleValue(), 0.001);

        Object min = container.queryItems("SELECT VALUE MIN(c.price) FROM c",
                new CosmosQueryRequestOptions(), Object.class).iterator().next();
        assertEquals(10, ((Number) min).intValue());

        Object max = container.queryItems("SELECT VALUE MAX(c.price) FROM c",
                new CosmosQueryRequestOptions(), Object.class).iterator().next();
        assertEquals(40, ((Number) max).intValue());

        db.delete();
    }

    @Test
    @DisplayName("SELECT DISTINCT returns unique projected documents")
    @SuppressWarnings("unchecked")
    void selectDistinct() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        for (int i = 0; i < 3; i++) container.createItem(doc("food-" + i, "food"));
        for (int i = 0; i < 2; i++) container.createItem(doc("book-" + i, "books"));

        List<Map> results = container.queryItems(
                "SELECT DISTINCT c.category FROM c",
                new CosmosQueryRequestOptions(), Map.class).stream().toList();

        List<String> categories = results.stream()
                .map(r -> (String) r.get("category")).sorted().toList();
        assertEquals(List.of("books", "food"), categories);

        db.delete();
    }

    @Test
    @DisplayName("GROUP BY with COUNT(1) groups and aggregates correctly")
    @SuppressWarnings("unchecked")
    void groupByCount() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        for (int i = 0; i < 3; i++) container.createItem(doc("food-" + i, "food"));
        for (int i = 0; i < 2; i++) container.createItem(doc("book-" + i, "books"));

        List<Map> results = container.queryItems(
                "SELECT c.category, COUNT(1) as count FROM c GROUP BY c.category",
                new CosmosQueryRequestOptions(), Map.class).stream().toList();

        Map<String, Integer> counts = new HashMap<>();
        results.forEach(r -> counts.put((String) r.get("category"), ((Number) r.get("count")).intValue()));
        assertEquals(Map.of("food", 3, "books", 2), counts);

        db.delete();
    }

    @Test
    @DisplayName("string functions LOWER/UPPER/LENGTH/CONCAT in WHERE and SELECT")
    @SuppressWarnings("unchecked")
    void stringFunctions() {
        String id = dbId();
        client.createDatabase(id);
        CosmosDatabase db = client.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        container.createItem(doc("item-1", "Electronics",
                "name", "Laptop Pro", "first", "John", "last", "Doe"));
        container.createItem(doc("item-2", "books",
                "name", "Guide", "first", "Jane", "last", "Smith"));

        CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();

        // WHERE with LOWER
        List<Map> r1 = container.queryItems(
                "SELECT * FROM c WHERE LOWER(c.category) = 'electronics'", opts, Map.class)
                .stream().toList();
        assertEquals(1, r1.size());
        assertEquals("item-1", r1.get(0).get("id"));

        // WHERE with UPPER
        List<Map> r2 = container.queryItems(
                "SELECT * FROM c WHERE UPPER(c.category) = 'BOOKS'", opts, Map.class)
                .stream().toList();
        assertEquals(1, r2.size());
        assertEquals("item-2", r2.get(0).get("id"));

        // WHERE with LENGTH  ("Laptop Pro"=10 > 5; "Guide"=5 is NOT > 5)
        List<Map> r3 = container.queryItems(
                "SELECT * FROM c WHERE LENGTH(c.name) > 5", opts, Map.class)
                .stream().toList();
        assertEquals(1, r3.size());
        assertEquals("item-1", r3.get(0).get("id"));

        // SELECT LOWER + LENGTH
        List<Map> r4 = container.queryItems(
                "SELECT LOWER(c.category) AS cat, LENGTH(c.name) AS nlen FROM c WHERE c.id = 'item-1'",
                opts, Map.class).stream().toList();
        assertEquals("electronics", r4.get(0).get("cat"));
        assertEquals(10, ((Number) r4.get(0).get("nlen")).intValue());

        // SELECT CONCAT
        List<Map> r5 = container.queryItems(
                "SELECT CONCAT(c.first, ' ', c.last) AS full_name FROM c WHERE c.id = 'item-1'",
                opts, Map.class).stream().toList();
        assertEquals("John Doe", r5.get(0).get("full_name"));

        db.delete();
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
