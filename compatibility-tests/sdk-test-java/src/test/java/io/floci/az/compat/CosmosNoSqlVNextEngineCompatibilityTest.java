package io.floci.az.compat;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.*;
import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compatibility tests for the Cosmos NoSQL VNext engine (cosmos-nosql).
 *
 * <p>Tests are skipped automatically when the NoSQL VNext engine is not enabled in floci-az config.
 * They require Docker and FLOCI_AZ_SERVICES_COSMOS_ENGINES_NOSQL_ENABLED=true.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Cosmos NoSQL VNext Engine Compatibility")
class CosmosNoSqlVNextEngineCompatibilityTest {

    private CosmosClient cosmosClient;
    private static final String COSMOS_KEY =
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";

    @BeforeAll
    void setup() throws InterruptedException {
        Map<String, Object> engineInfo = EmulatorConfig.triggerCosmosEngine("cosmos-nosql");
        assumeTrue(engineInfo != null, "NoSQL VNext engine not enabled — skipping tests");

        String host = (String) engineInfo.get("host");
        int port = ((Number) engineInfo.get("port")).intValue();
        String endpoint = "https://" + host + ":" + port;

        // Retry connection up to 30s — container may still be starting
        Exception lastException = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                CosmosClient candidate = new CosmosClientBuilder()
                        .endpoint(endpoint)
                        .key(COSMOS_KEY)
                        .gatewayMode()
                        .endpointDiscoveryEnabled(false)
                        .buildClient();
                // Smoke test
                candidate.readAllDatabases().stream().findFirst();
                cosmosClient = candidate;
                lastException = null;
                break;
            } catch (Exception e) {
                lastException = e;
                if (cosmosClient != null) {
                    try { cosmosClient.close(); } catch (Exception ignored) {}
                    cosmosClient = null;
                }
                Thread.sleep(2_000);
            }
        }
        if (lastException != null) {
            assumeTrue(false, "Cosmos NoSQL VNext not ready after 30s: " + lastException.getMessage());
        }
    }

    @AfterAll
    void teardown() {
        if (cosmosClient != null) {
            cosmosClient.close();
        }
    }

    private String dbId() {
        return "vnext-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
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

    @Test
    @DisplayName("database lifecycle: create → list → delete")
    void databaseLifecycle() {
        String id = dbId();
        cosmosClient.createDatabase(id);

        List<String> ids = cosmosClient.readAllDatabases().stream()
                .map(CosmosDatabaseProperties::getId).toList();
        assertTrue(ids.contains(id), "Database should be listed after creation");

        cosmosClient.getDatabase(id).delete();

        List<String> after = cosmosClient.readAllDatabases().stream()
                .map(CosmosDatabaseProperties::getId).toList();
        assertFalse(after.contains(id), "Database should not be listed after deletion");
    }

    @Test
    @DisplayName("document CRUD: create → read → replace → delete")
    @SuppressWarnings("unchecked")
    void documentCrud() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        // Create
        Map<String, Object> item = doc("doc-1", "test", "name", "Item One", "price", 100);
        CosmosItemResponse<Map> created = container.createItem(item,
                new PartitionKey("test"), new CosmosItemRequestOptions());
        assertEquals(201, created.getStatusCode());

        // Read
        Map<String, Object> read = container
                .readItem("doc-1", new PartitionKey("test"), Map.class).getItem();
        assertEquals("Item One", read.get("name"));
        assertEquals(100, ((Number) read.get("price")).intValue());

        // Replace
        read.put("price", 50);
        container.replaceItem(read, "doc-1", new PartitionKey("test"), new CosmosItemRequestOptions());
        Map<String, Object> refreshed = container
                .readItem("doc-1", new PartitionKey("test"), Map.class).getItem();
        assertEquals(50, ((Number) refreshed.get("price")).intValue());

        // Delete
        container.deleteItem("doc-1", new PartitionKey("test"), new CosmosItemRequestOptions());
        CosmosException ex = assertThrows(CosmosException.class,
                () -> container.readItem("doc-1", new PartitionKey("test"), Map.class));
        assertEquals(404, ex.getStatusCode());

        db.delete();
    }

    @Test
    @DisplayName("simple query: INSERT 2, SELECT WHERE price > 100 returns 1")
    @SuppressWarnings("unchecked")
    void simpleQuery() {
        String id = dbId();
        cosmosClient.createDatabase(id);
        CosmosDatabase db = cosmosClient.getDatabase(id);
        db.createContainerIfNotExists("items", "/category");
        CosmosContainer container = db.getContainer("items");

        container.createItem(doc("cheap", "qry", "price", 10));
        container.createItem(doc("expensive", "qry", "price", 500));

        SqlQuerySpec spec = new SqlQuerySpec(
                "SELECT * FROM c WHERE c.price > @min",
                java.util.Collections.singletonList(new SqlParameter("@min", 100)));

        List<Map> results = container
                .queryItems(spec, new CosmosQueryRequestOptions(), Map.class)
                .stream().toList();
        assertEquals(1, results.size());
        assertEquals("expensive", results.get(0).get("id"));

        db.delete();
    }
}
