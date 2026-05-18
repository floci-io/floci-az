package io.floci.az.compat;

import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableServiceException;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compatibility tests for the Cosmos Table engine (cosmos-table).
 *
 * <p>This tests the Cosmos Table API backed by an Azurite sidecar, which is distinct from
 * the {@link TableCompatibilityTest} that tests Azure Table Storage via the blob/queue path.
 * Tests are skipped automatically when the Table engine is not enabled in floci-az config.
 * They require Docker and FLOCI_AZ_SERVICES_COSMOS_ENGINES_TABLE_ENABLED=true.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Cosmos Table Engine Compatibility")
class CosmosTableEngineCompatibilityTest {

    private TableServiceClient tableServiceClient;
    private TableClient tableClient;
    private static final String TABLE_NAME = "EngineTestTable";

    @BeforeAll
    void setup() throws InterruptedException {
        Map<String, Object> engineInfo = EmulatorConfig.triggerCosmosEngine("cosmos-table");
        assumeTrue(engineInfo != null, "Table engine not enabled — skipping tests");

        String connectionString = (String) engineInfo.get("connectionString");
        assertNotNull(connectionString, "connectionString must not be null in engine response");

        // Retry connection up to 30s — container may still be starting
        Exception lastException = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                TableServiceClient candidate = new TableServiceClientBuilder()
                        .connectionString(connectionString)
                        .buildClient();
                // Smoke test — list tables
                candidate.listTables().stream().findFirst(); // may return empty, that's fine
                tableServiceClient = candidate;
                lastException = null;
                break;
            } catch (Exception e) {
                lastException = e;
                tableServiceClient = null;
                Thread.sleep(2_000);
            }
        }
        if (lastException != null) {
            assumeTrue(false, "Table engine not ready after 30s: " + lastException.getMessage());
        }

        tableServiceClient.createTableIfNotExists(TABLE_NAME);
        tableClient = tableServiceClient.getTableClient(TABLE_NAME);
    }

    @AfterAll
    void teardown() {
        if (tableServiceClient != null) {
            try {
                tableServiceClient.deleteTable(TABLE_NAME);
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("createAndGetEntity: upsert entity, getEntity, verify properties")
    void createAndGetEntity() {
        TableEntity entity = new TableEntity("pk-create", "rk-1")
                .addProperty("name", "TestItem")
                .addProperty("value", 42);
        tableClient.upsertEntity(entity);

        TableEntity found = tableClient.getEntity("pk-create", "rk-1");
        assertNotNull(found);
        assertEquals("TestItem", found.getProperty("name"));
        assertEquals(42, ((Number) found.getProperty("value")).intValue());
    }

    @Test
    @DisplayName("updateEntity: upsert twice with same keys, verify updated field")
    void updateEntity() {
        TableEntity v1 = new TableEntity("pk-update", "rk-1")
                .addProperty("status", "draft");
        tableClient.upsertEntity(v1);

        TableEntity v2 = new TableEntity("pk-update", "rk-1")
                .addProperty("status", "published");
        tableClient.upsertEntity(v2);

        TableEntity found = tableClient.getEntity("pk-update", "rk-1");
        assertEquals("published", found.getProperty("status"));
    }

    @Test
    @DisplayName("deleteEntity: upsert, delete, getEntity should throw")
    void deleteEntity() {
        TableEntity entity = new TableEntity("pk-delete", "rk-1")
                .addProperty("temp", true);
        tableClient.upsertEntity(entity);
        tableClient.deleteEntity("pk-delete", "rk-1");

        assertThrows(TableServiceException.class,
                () -> tableClient.getEntity("pk-delete", "rk-1"),
                "Entity should not exist after deletion");
    }

    @Test
    @DisplayName("listEntities: upsert 3 entities with same partitionKey, OData filter returns all 3")
    void listEntities() {
        String pk = "pk-list";
        tableClient.upsertEntity(new TableEntity(pk, "rk-a").addProperty("idx", 1));
        tableClient.upsertEntity(new TableEntity(pk, "rk-b").addProperty("idx", 2));
        tableClient.upsertEntity(new TableEntity(pk, "rk-c").addProperty("idx", 3));

        ListEntitiesOptions opts = new ListEntitiesOptions()
                .setFilter("PartitionKey eq '" + pk + "'");
        List<TableEntity> results = tableClient.listEntities(opts, null, null).stream().toList();

        assertEquals(3, results.size(), "Expected 3 entities with partitionKey=" + pk);
    }
}
