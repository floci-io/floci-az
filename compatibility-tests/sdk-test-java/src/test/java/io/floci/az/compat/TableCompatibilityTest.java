package io.floci.az.compat;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.data.tables.models.TableServiceException;
import com.azure.data.tables.models.TableTransactionAction;
import com.azure.data.tables.models.TableTransactionActionType;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Table Storage Compatibility")
class TableCompatibilityTest {

    private TableServiceClient client;

    @BeforeAll
    void setup() {
        client = new TableServiceClientBuilder()
            .connectionString(EmulatorConfig.TABLE_CONN)
            .buildClient();
    }

    private String tableName() {
        return "test" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    // --- Golden path ---

    @Test
    @DisplayName("table lifecycle: create → list → entity CRUD → delete")
    void tableLifecycle() {
        String name = tableName();
        TableClient table = client.createTable(name);

        List<String> tables = client.listTables().stream().map(t -> t.getName()).toList();
        assertTrue(tables.contains(name));

        TableEntity entity = new TableEntity("p1", "r1").addProperty("Value", "hello");
        table.createEntity(entity);

        TableEntity received = table.getEntity("p1", "r1");
        assertEquals("hello", received.getProperty("Value"));

        List<TableEntity> entities = table.listEntities().stream().toList();
        assertEquals(1, entities.size());
        assertEquals("p1", entities.get(0).getPartitionKey());

        table.deleteEntity("p1", "r1");
        client.deleteTable(name);

        List<String> after = client.listTables().stream().map(t -> t.getName()).toList();
        assertFalse(after.contains(name));
    }

    @Test
    @DisplayName("entity upsert: second upsert updates value")
    void entityUpsert() {
        String name = tableName();
        TableClient table = client.createTable(name);

        table.createEntity(new TableEntity("p1", "r1").addProperty("Value", "original"));
        table.upsertEntity(new TableEntity("p1", "r1").addProperty("Value", "updated"));

        TableEntity received = table.getEntity("p1", "r1");
        assertEquals("updated", received.getProperty("Value"));

        client.deleteTable(name);
    }

    @Test
    @DisplayName("multiple entities: insert 5 → list → count matches")
    void multipleEntities() {
        String name = tableName();
        TableClient table = client.createTable(name);

        for (int i = 0; i < 5; i++) {
            table.createEntity(new TableEntity("p1", "r" + i).addProperty("Index", i));
        }

        long count = table.listEntities().stream().count();
        assertEquals(5, count);

        client.deleteTable(name);
    }

    // --- Error cases ---

    @Test
    @DisplayName("get missing entity → TableServiceException (404)")
    void entityNotFound() {
        String name = tableName();
        TableClient table = client.createTable(name);

        TableServiceException ex = assertThrows(TableServiceException.class,
            () -> table.getEntity("no-pk", "no-rk"));
        assertEquals(404, ex.getResponse().getStatusCode());

        client.deleteTable(name);
    }

    @Test
    @DisplayName("create duplicate table → TableServiceException (409)")
    void tableAlreadyExists() {
        String name = tableName();
        client.createTable(name);

        TableServiceException ex = assertThrows(TableServiceException.class,
            () -> client.createTable(name));
        assertEquals(409, ex.getResponse().getStatusCode());

        client.deleteTable(name);
    }

    // --- Query / filter tests ---

    @Test
    @DisplayName("filter by PartitionKey: only matching partition returned")
    void filterByPartitionKey() {
        String name = tableName();
        TableClient table = client.createTable(name);

        table.createEntity(new TableEntity("p1", "r1").addProperty("Value", "a"));
        table.createEntity(new TableEntity("p1", "r2").addProperty("Value", "b"));
        table.createEntity(new TableEntity("p2", "r1").addProperty("Value", "c"));

        ListEntitiesOptions opts = new ListEntitiesOptions().setFilter("PartitionKey eq 'p1'");
        List<TableEntity> entities = table.listEntities(opts, null, null).stream().toList();

        assertEquals(2, entities.size());
        assertTrue(entities.stream().allMatch(e -> "p1".equals(e.getPartitionKey())));

        client.deleteTable(name);
    }

    @Test
    @DisplayName("filter by numeric field: Score gt 20 returns only matching entities")
    void filterByNumericField() {
        String name = tableName();
        TableClient table = client.createTable(name);

        table.createEntity(new TableEntity("p1", "r1").addProperty("Score", 10));
        table.createEntity(new TableEntity("p1", "r2").addProperty("Score", 50));
        table.createEntity(new TableEntity("p1", "r3").addProperty("Score", 80));

        ListEntitiesOptions opts = new ListEntitiesOptions().setFilter("Score gt 20");
        List<TableEntity> entities = table.listEntities(opts, null, null).stream().toList();

        assertEquals(2, entities.size());
        assertTrue(entities.stream().allMatch(e -> {
            Object score = e.getProperty("Score");
            return score instanceof Number && ((Number) score).intValue() > 20;
        }));

        client.deleteTable(name);
    }

    @Test
    @DisplayName("$select fields: only requested properties returned")
    void selectFields() {
        String name = tableName();
        TableClient table = client.createTable(name);

        table.createEntity(new TableEntity("p1", "r1")
            .addProperty("Name", "Alice")
            .addProperty("Age", 30)
            .addProperty("City", "NYC"));

        ListEntitiesOptions opts = new ListEntitiesOptions()
            .setFilter("PartitionKey eq 'p1'")
            .setSelect(List.of("Name", "Age"));
        List<TableEntity> entities = table.listEntities(opts, null, null).stream().toList();

        assertEquals(1, entities.size());
        TableEntity e = entities.get(0);
        assertEquals("Alice", e.getProperty("Name"));
        assertEquals(30, ((Number) e.getProperty("Age")).intValue());
        assertNull(e.getProperty("City"));
        assertNotNull(e.getPartitionKey());

        client.deleteTable(name);
    }

    @Test
    @DisplayName("pagination with $top: multiple pages returned, total count correct")
    void pagination() {
        String name = tableName();
        TableClient table = client.createTable(name);

        for (int i = 0; i < 10; i++) {
            table.createEntity(new TableEntity("p1", String.format("r%02d", i)).addProperty("Index", i));
        }

        ListEntitiesOptions opts = new ListEntitiesOptions()
            .setFilter("PartitionKey eq 'p1'")
            .setTop(3);

        AtomicInteger totalEntities = new AtomicInteger(0);
        AtomicInteger pageCount = new AtomicInteger(0);

        table.listEntities(opts, null, null).iterableByPage().forEach(page -> {
            pageCount.incrementAndGet();
            page.getElements().forEach(e -> totalEntities.incrementAndGet());
        });

        assertEquals(10, totalEntities.get());
        assertTrue(pageCount.get() >= 2, "Expected at least 2 pages but got " + pageCount.get());

        client.deleteTable(name);
    }

    @Test
    @DisplayName("etag concurrency: stale etag on delete → 412 Precondition Failed")
    void etagConcurrency() {
        String name = tableName();
        TableClient table = client.createTable(name);

        table.createEntity(new TableEntity("p1", "r1").addProperty("Value", "v1"));

        TableEntity entity = table.getEntity("p1", "r1");
        String staleEtag = entity.getETag();
        assertNotNull(staleEtag);

        // Update the entity so the stored etag changes
        table.updateEntity(
            new TableEntity("p1", "r1").addProperty("Value", "v2"),
            TableEntityUpdateMode.REPLACE);

        // Attempt to update using the now-stale etag — must fail with 412
        // updateEntityWithResponse(entity, mode, ifUnchanged=true) sends If-Match: <entity.etag>
        TableServiceException ex = assertThrows(TableServiceException.class,
            () -> table.updateEntityWithResponse(entity, TableEntityUpdateMode.REPLACE, true, null, null));
        assertEquals(412, ex.getResponse().getStatusCode());

        client.deleteTable(name);
    }

    @Test
    @DisplayName("batch transaction: create two, then upsert one and delete the other")
    void batchTransaction() {
        String name = tableName();
        TableClient table = client.createTable(name);

        // First batch: create two entities
        List<TableTransactionAction> createActions = List.of(
            new TableTransactionAction(TableTransactionActionType.CREATE,
                new TableEntity("p1", "r1").addProperty("Value", "hello")),
            new TableTransactionAction(TableTransactionActionType.CREATE,
                new TableEntity("p1", "r2").addProperty("Value", "world"))
        );
        table.submitTransaction(createActions);

        assertEquals("hello", table.getEntity("p1", "r1").getProperty("Value"));
        assertEquals("world", table.getEntity("p1", "r2").getProperty("Value"));

        // Second batch: upsert r1, delete r2
        List<TableTransactionAction> updateActions = List.of(
            new TableTransactionAction(TableTransactionActionType.UPSERT_REPLACE,
                new TableEntity("p1", "r1").addProperty("Value", "updated")),
            new TableTransactionAction(TableTransactionActionType.DELETE,
                new TableEntity("p1", "r2"))
        );
        table.submitTransaction(updateActions);

        assertEquals("updated", table.getEntity("p1", "r1").getProperty("Value"));

        TableServiceException ex = assertThrows(TableServiceException.class,
            () -> table.getEntity("p1", "r2"));
        assertEquals(404, ex.getResponse().getStatusCode());

        client.deleteTable(name);
    }
}
