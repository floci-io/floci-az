package io.floci.az.compat;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableServiceException;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

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
}
