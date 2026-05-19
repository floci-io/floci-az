package io.floci.az.compat;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compatibility tests for the embedded Cosmos DB NoSQL engine ({@code cosmos-nosql}).
 *
 * <p>This engine is in-process — no Docker required. It implements the
 * <a href="https://learn.microsoft.com/en-us/azure/cosmos-db/nosql/query/overview">
 * Azure Cosmos DB NoSQL SQL dialect</a> including:
 * WHERE, ORDER BY, GROUP BY, aggregates (COUNT, SUM, AVG, MIN, MAX), DISTINCT,
 * LIKE, IIF, and a full set of string, math, and array functions.
 *
 * <p>Tests are skipped automatically when the NoSQL engine is not enabled.
 * Set {@code FLOCI_AZ_SERVICES_COSMOS_ENGINES_NOSQL_ENABLED=true} to run them.
 *
 * <p>The Java Cosmos SDK (gateway mode) enforces TLS — connect to
 * {@code https://localhost:4578} using the bundled self-signed certificate.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Cosmos NoSQL Embedded Engine Compatibility")
class CosmosNoSqlEngineCompatibilityTest {

    private CosmosClient cosmosClient;

    @BeforeAll
    void setup() {
        // Trigger engine start (no-op for embedded, but confirms the engine is enabled)
        Map<String, Object> engineInfo = EmulatorConfig.triggerCosmosEngine("cosmos-nosql");
        assumeTrue(engineInfo != null, "NoSQL embedded engine not enabled — skipping tests");

        // Embedded engine: Java SDK connects via the standard floci-az HTTPS endpoint.
        cosmosClient = EmulatorConfig.buildCosmosClient();
    }

    @AfterAll
    void teardown() {
        if (cosmosClient != null) cosmosClient.close();
    }

    private String dbId() {
        return "nosql-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private Map<String, Object> doc(String id, String category, Object... extras) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("category", category);
        for (int i = 0; i < extras.length - 1; i += 2)
            map.put(extras[i].toString(), extras[i + 1]);
        return map;
    }

    // ── SQL queries ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("SELECT * FROM c WHERE c.price > @min returns matching documents")
    @SuppressWarnings("unchecked")
    void queryWhere() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        c.createItem(doc("cheap",     "misc", "price", 10));
        c.createItem(doc("expensive", "misc", "price", 500));

        SqlQuerySpec spec = new SqlQuerySpec(
                "SELECT * FROM c WHERE c.price > @min",
                Collections.singletonList(new SqlParameter("@min", 100)));

        List<Map> results = c.queryItems(spec, new CosmosQueryRequestOptions(), Map.class)
                .stream().toList();
        assertEquals(1, results.size());
        assertEquals("expensive", results.get(0).get("id"));

        db.delete();
    }

    @Test
    @DisplayName("SELECT * FROM c ORDER BY c.rank ASC sorts documents")
    @SuppressWarnings("unchecked")
    void queryOrderBy() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        c.createItem(doc("c", "sort", "rank", 3));
        c.createItem(doc("a", "sort", "rank", 1));
        c.createItem(doc("b", "sort", "rank", 2));

        List<Map> results = c.queryItems("SELECT * FROM c ORDER BY c.rank ASC",
                new CosmosQueryRequestOptions(), Map.class).stream().toList();

        List<Integer> ranks = results.stream().map(r -> ((Number) r.get("rank")).intValue()).toList();
        assertEquals(List.of(1, 2, 3), ranks);

        db.delete();
    }

    @Test
    @DisplayName("SELECT VALUE COUNT(1) returns total document count")
    void queryCount() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        for (int i = 0; i < 4; i++) c.createItem(doc("item-" + i, "cnt"));

        Object count = c.queryItems("SELECT VALUE COUNT(1) FROM c",
                new CosmosQueryRequestOptions(), Object.class).iterator().next();
        assertEquals(4, ((Number) count).intValue());

        db.delete();
    }

    @Test
    @DisplayName("SELECT VALUE SUM/AVG/MIN/MAX return correct scalar aggregates")
    void aggregateFunctions() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        int[] prices = {10, 20, 30, 40}; // sum=100, avg=25, min=10, max=40
        for (int i = 0; i < prices.length; i++)
            c.createItem(doc("item-" + i, "agg", "price", prices[i]));

        assertEquals(100, ((Number) c.queryItems("SELECT VALUE SUM(c.price) FROM c",
                new CosmosQueryRequestOptions(), Object.class).iterator().next()).intValue());
        assertEquals(25.0, ((Number) c.queryItems("SELECT VALUE AVG(c.price) FROM c",
                new CosmosQueryRequestOptions(), Object.class).iterator().next()).doubleValue(), 0.001);
        assertEquals(10, ((Number) c.queryItems("SELECT VALUE MIN(c.price) FROM c",
                new CosmosQueryRequestOptions(), Object.class).iterator().next()).intValue());
        assertEquals(40, ((Number) c.queryItems("SELECT VALUE MAX(c.price) FROM c",
                new CosmosQueryRequestOptions(), Object.class).iterator().next()).intValue());

        db.delete();
    }

    @Test
    @DisplayName("SELECT DISTINCT returns deduplicated projected documents")
    @SuppressWarnings("unchecked")
    void selectDistinct() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        for (int i = 0; i < 3; i++) c.createItem(doc("food-" + i, "food"));
        for (int i = 0; i < 2; i++) c.createItem(doc("book-" + i, "books"));

        List<Map> results = c.queryItems("SELECT DISTINCT c.category FROM c",
                new CosmosQueryRequestOptions(), Map.class).stream().toList();

        List<String> cats = results.stream().map(r -> (String) r.get("category")).sorted().toList();
        assertEquals(List.of("books", "food"), cats);

        db.delete();
    }

    @Test
    @DisplayName("GROUP BY with COUNT(1) groups and counts correctly")
    @SuppressWarnings("unchecked")
    void groupByCount() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        for (int i = 0; i < 3; i++) c.createItem(doc("food-" + i, "food"));
        for (int i = 0; i < 2; i++) c.createItem(doc("book-" + i, "books"));

        List<Map> results = c.queryItems(
                "SELECT c.category, COUNT(1) as count FROM c GROUP BY c.category",
                new CosmosQueryRequestOptions(), Map.class).stream().toList();

        Map<String, Integer> counts = new HashMap<>();
        results.forEach(r -> counts.put((String) r.get("category"), ((Number) r.get("count")).intValue()));
        assertEquals(Map.of("food", 3, "books", 2), counts);

        db.delete();
    }

    @Test
    @DisplayName("LOWER/UPPER/LENGTH/CONCAT string functions in WHERE and SELECT")
    @SuppressWarnings("unchecked")
    void stringFunctions() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        c.createItem(doc("item-1", "Electronics", "name", "Laptop Pro", "first", "John", "last", "Doe"));
        c.createItem(doc("item-2", "books",        "name", "Guide",      "first", "Jane", "last", "Smith"));

        CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();

        // WHERE LOWER
        List<Map> r1 = c.queryItems("SELECT * FROM c WHERE LOWER(c.category) = 'electronics'", opts, Map.class)
                .stream().toList();
        assertEquals(1, r1.size());
        assertEquals("item-1", r1.get(0).get("id"));

        // WHERE LENGTH
        List<Map> r2 = c.queryItems("SELECT * FROM c WHERE LENGTH(c.name) > 5", opts, Map.class)
                .stream().toList();
        assertEquals(1, r2.size());
        assertEquals("item-1", r2.get(0).get("id"));

        // SELECT CONCAT
        List<Map> r3 = c.queryItems(
                "SELECT CONCAT(c.first, ' ', c.last) AS full_name FROM c WHERE c.id = 'item-1'",
                opts, Map.class).stream().toList();
        assertEquals("John Doe", r3.get(0).get("full_name"));

        db.delete();
    }

    @Test
    @DisplayName("WHERE c.name LIKE '%ro%' matches pattern")
    @SuppressWarnings("unchecked")
    void likeFilter() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        c.createItem(doc("a", "test", "name", "Laptop Pro"));
        c.createItem(doc("b", "test", "name", "Keyboard"));

        List<Map> results = c.queryItems("SELECT * FROM c WHERE c.name LIKE '%Pro%'",
                new CosmosQueryRequestOptions(), Map.class).stream().toList();

        assertEquals(1, results.size());
        assertEquals("a", results.get(0).get("id"));

        db.delete();
    }

    @Test
    @DisplayName("IIF(condition, trueVal, falseVal) returns correct branch")
    @SuppressWarnings("unchecked")
    void iifExpression() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        c.createItem(doc("cheap",     "test", "price", 5));
        c.createItem(doc("expensive", "test", "price", 200));

        List<Map> results = c.queryItems(
                "SELECT c.id, IIF(c.price > 100, 'expensive', 'cheap') AS tier FROM c",
                new CosmosQueryRequestOptions(), Map.class).stream().toList();

        Map<String, String> tiers = new HashMap<>();
        results.forEach(r -> tiers.put((String) r.get("id"), (String) r.get("tier")));

        assertEquals("cheap",     tiers.get("cheap"));
        assertEquals("expensive", tiers.get("expensive"));

        db.delete();
    }

    @Test
    @DisplayName("OFFSET 1 LIMIT 2 returns the correct page")
    @SuppressWarnings("unchecked")
    void offsetLimit() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        for (int i = 0; i < 5; i++) c.createItem(doc("item-" + i, "pg", "rank", i));

        List<Map> results = c.queryItems(
                "SELECT * FROM c ORDER BY c.rank OFFSET 1 LIMIT 2",
                new CosmosQueryRequestOptions(), Map.class).stream().toList();

        assertEquals(2, results.size());
        assertEquals(1, ((Number) results.get(0).get("rank")).intValue());
        assertEquals(2, ((Number) results.get(1).get("rank")).intValue());

        db.delete();
    }

    @Test
    @DisplayName("Math functions ABS, CEILING, FLOOR, ROUND, SQRT work in SELECT")
    @SuppressWarnings("unchecked")
    void mathFunctions() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        c.createItem(doc("doc1", "math", "val", -4.7));

        List<Map> results = c.queryItems(
                "SELECT ABS(c.val) AS abs, CEILING(c.val) AS ceil, FLOOR(c.val) AS floor, " +
                "ROUND(c.val) AS rnd, SQRT(ABS(c.val)) AS sqr FROM c WHERE c.id = 'doc1'",
                new CosmosQueryRequestOptions(), Map.class).stream().toList();

        Map<String, Object> r = results.get(0);
        assertEquals(4.7,  ((Number) r.get("abs")).doubleValue(),  0.001);
        assertEquals(-4,   ((Number) r.get("ceil")).intValue());
        assertEquals(-5,   ((Number) r.get("floor")).intValue());
        assertEquals(-5,   ((Number) r.get("rnd")).intValue());
        assertEquals(2.167, ((Number) r.get("sqr")).doubleValue(), 0.01);

        db.delete();
    }

    @Test
    @DisplayName("ARRAY_LENGTH and ARRAY_SLICE array functions work in SELECT")
    @SuppressWarnings("unchecked")
    void arrayFunctions() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        c.createItem(doc("doc1", "arr", "tags", List.of("a", "b", "c", "d")));

        List<Map> results = c.queryItems(
                "SELECT ARRAY_LENGTH(c.tags) AS len, ARRAY_SLICE(c.tags, 1, 2) AS slice FROM c WHERE c.id = 'doc1'",
                new CosmosQueryRequestOptions(), Map.class).stream().toList();

        Map<String, Object> r = results.get(0);
        assertEquals(4, ((Number) r.get("len")).intValue());
        assertEquals(List.of("b", "c"), r.get("slice"));

        db.delete();
    }

    @Test
    @DisplayName("IS_DEFINED, IS_STRING, IS_NUMBER type-check functions work in WHERE")
    @SuppressWarnings("unchecked")
    void typeFunctions() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        c.createItem(doc("with-name",    "t", "name", "Alice", "score", 99));
        c.createItem(doc("without-name", "t", "score", 50));

        CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();

        List<Map> defined = c.queryItems("SELECT * FROM c WHERE IS_DEFINED(c.name)", opts, Map.class)
                .stream().toList();
        assertEquals(1, defined.size());
        assertEquals("with-name", defined.get(0).get("id"));

        List<Map> numbers = c.queryItems("SELECT * FROM c WHERE IS_NUMBER(c.score)", opts, Map.class)
                .stream().toList();
        assertEquals(2, numbers.size());

        db.delete();
    }

    @Test
    @DisplayName("pagination: x-ms-max-item-count splits results into pages")
    @SuppressWarnings("unchecked")
    void pagination() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        int total = 10;
        for (int i = 0; i < total; i++)
            c.createItem(doc(String.format("item-%02d", i), "pg", "rank", i));

        int pageSize = 3;
        List<String> allIds = new ArrayList<>();
        int pageCount = 0;

        for (var page : c.queryItems("SELECT * FROM c", new CosmosQueryRequestOptions(), Map.class)
                .iterableByPage(pageSize)) {
            assertTrue(page.getResults().size() <= pageSize);
            page.getResults().forEach(item -> allIds.add((String) item.get("id")));
            pageCount++;
        }

        assertTrue(pageCount >= 2);
        assertEquals(total, allIds.size());

        db.delete();
    }

    @Test
    @DisplayName("CONTAINS, STARTSWITH, ENDSWITH string predicates in WHERE")
    @SuppressWarnings("unchecked")
    void stringPredicates() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer c = db.getContainer("items");

        c.createItem(doc("a", "t", "name", "Azure Cosmos DB"));
        c.createItem(doc("b", "t", "name", "Amazon DynamoDB"));
        c.createItem(doc("c", "t", "name", "Google Firestore"));

        CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();

        assertEquals(2, c.queryItems("SELECT * FROM c WHERE CONTAINS(c.name, 'DB')", opts, Map.class)
                .stream().count());
        assertEquals(1, c.queryItems("SELECT * FROM c WHERE STARTSWITH(c.name, 'Azure')", opts, Map.class)
                .stream().count());
        assertEquals(1, c.queryItems("SELECT * FROM c WHERE ENDSWITH(c.name, 'store')", opts, Map.class)
                .stream().count());

        db.delete();
    }
}
