package io.floci.az.compat;

import com.azure.core.credential.AzureNamedKeyCredential;
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
 * Compatibility tests for the Cosmos DB for Table engine (cosmos-table).
 *
 * <p>Uses the <b>official Azure Cosmos DB for Table SDK pattern</b>:
 * {@code TableServiceClientBuilder.endpoint().credential(AzureNamedKeyCredential)},
 * matching the pattern documented at
 * <a href="https://learn.microsoft.com/en-us/azure/cosmos-db/table/quickstart-java">
 * Azure Cosmos DB for Table — Java quickstart</a>.
 *
 * <p>The engine is in-memory (no Docker). Tests are skipped when the Table engine is not
 * enabled — set {@code FLOCI_AZ_SERVICES_COSMOS_ENGINES_TABLE_ENABLED=true} to run them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Cosmos Table Engine Compatibility")
class CosmosTableEngineCompatibilityTest {

    // Well-known Azurite / floci-az development account key
    private static final String ACCOUNT_NAME = "devstoreaccount1";
    private static final String ACCOUNT_KEY  =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==";

    private TableServiceClient tableServiceClient;
    private TableClient        tableClient;
    private static final String TABLE_NAME = "CosmicworksProducts";

    @BeforeAll
    void setup() {
        Map<String, Object> engineInfo = EmulatorConfig.triggerCosmosEngine("cosmos-table");
        assumeTrue(engineInfo != null, "Table engine not enabled — skipping tests");

        // Build the endpoint URL from the connection info returned by /connect.
        // Format: http://<host>:<port>/devstoreaccount1-cosmos-table
        String host = (String) engineInfo.get("host");
        int    port = ((Number) engineInfo.get("port")).intValue();
        String endpoint = "http://" + host + ":" + port + "/devstoreaccount1-cosmos-table";

        // Official Cosmos DB for Table pattern:
        //   new TableServiceClientBuilder()
        //       .endpoint("<cosmos-table-account-endpoint>")
        //       .credential(new AzureNamedKeyCredential(accountName, accountKey))
        //       .buildClient();
        // Reference: https://learn.microsoft.com/en-us/azure/cosmos-db/table/quickstart-java
        tableServiceClient = new TableServiceClientBuilder()
                .endpoint(endpoint)
                .credential(new AzureNamedKeyCredential(ACCOUNT_NAME, ACCOUNT_KEY))
                .buildClient();

        // Smoke test — list tables (may return empty, that's fine)
        tableServiceClient.listTables().stream().findFirst();

        tableServiceClient.createTableIfNotExists(TABLE_NAME);
        tableClient = tableServiceClient.getTableClient(TABLE_NAME);
    }

    @AfterAll
    void teardown() {
        if (tableServiceClient != null) {
            try { tableServiceClient.deleteTable(TABLE_NAME); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("createAndGetEntity: upsert entity, getEntity, verify properties")
    void createAndGetEntity() {
        // Mirrors the Cosmos DB for Table quickstart entity schema:
        // partitionKey = category, rowKey = UUID-style id
        TableEntity entity = new TableEntity("gear-surf-surfboards", "aaaaaaaa-0000-1111-2222-bbbbbbbbbbbb")
                .addProperty("Name", "Yamba Surfboard")
                .addProperty("Quantity", 12)
                .addProperty("Price", 850.00)
                .addProperty("Sale", false);
        tableClient.upsertEntity(entity);

        TableEntity found = tableClient.getEntity("gear-surf-surfboards", "aaaaaaaa-0000-1111-2222-bbbbbbbbbbbb");
        assertNotNull(found);
        assertEquals("Yamba Surfboard",        found.getProperty("Name"));
        assertEquals(12,   ((Number)  found.getProperty("Quantity")).intValue());
        assertEquals(850.0, ((Number) found.getProperty("Price")).doubleValue(), 0.001);
        assertEquals(false,            found.getProperty("Sale"));
    }

    @Test
    @DisplayName("updateEntity: upsert twice with same keys, verify updated field")
    void updateEntity() {
        TableEntity v1 = new TableEntity("gear-surf-surfboards", "bbbbbbbb-1111-2222-3333-cccccccccccc")
                .addProperty("Name", "Kiama Classic Surfboard")
                .addProperty("Quantity", 25);
        tableClient.upsertEntity(v1);

        TableEntity v2 = new TableEntity("gear-surf-surfboards", "bbbbbbbb-1111-2222-3333-cccccccccccc")
                .addProperty("Name", "Kiama Classic Surfboard")
                .addProperty("Quantity", 10);   // stock updated
        tableClient.upsertEntity(v2);

        TableEntity found = tableClient.getEntity("gear-surf-surfboards", "bbbbbbbb-1111-2222-3333-cccccccccccc");
        assertEquals(10, ((Number) found.getProperty("Quantity")).intValue());
    }

    @Test
    @DisplayName("deleteEntity: upsert, delete, getEntity should throw 404")
    void deleteEntity() {
        TableEntity entity = new TableEntity("gear-climb-harnesses", "cccccccc-2222-3333-4444-dddddddddddd")
                .addProperty("Name", "Novatech Harness")
                .addProperty("Quantity", 5);
        tableClient.upsertEntity(entity);
        tableClient.deleteEntity("gear-climb-harnesses", "cccccccc-2222-3333-4444-dddddddddddd");

        assertThrows(TableServiceException.class,
                () -> tableClient.getEntity("gear-climb-harnesses", "cccccccc-2222-3333-4444-dddddddddddd"),
                "Entity should not exist after deletion");
    }

    @Test
    @DisplayName("queryEntities: upsert 3 items, OData filter by PartitionKey returns all 3")
    void queryEntities() {
        // Mirrors the official quickstart query pattern:
        //   ListEntitiesOptions().setFilter("PartitionKey eq 'gear-surf-surfboards'")
        String pk = "gear-kayaking-accessories";
        tableClient.upsertEntity(new TableEntity(pk, "dddddddd-0001-0001-0001-000000000001").addProperty("Name", "Paddle A"));
        tableClient.upsertEntity(new TableEntity(pk, "dddddddd-0001-0001-0001-000000000002").addProperty("Name", "Paddle B"));
        tableClient.upsertEntity(new TableEntity(pk, "dddddddd-0001-0001-0001-000000000003").addProperty("Name", "Paddle C"));

        ListEntitiesOptions opts = new ListEntitiesOptions()
                .setFilter("PartitionKey eq '" + pk + "'");
        List<TableEntity> results = tableClient.listEntities(opts, null, null).stream().toList();

        assertEquals(3, results.size(), "Expected 3 entities for partitionKey=" + pk);
        assertTrue(results.stream().allMatch(e -> pk.equals(e.getPartitionKey())));
    }
}
