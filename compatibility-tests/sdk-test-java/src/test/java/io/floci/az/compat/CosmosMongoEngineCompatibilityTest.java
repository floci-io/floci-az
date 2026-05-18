package io.floci.az.compat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compatibility tests for the Cosmos MongoDB engine (cosmos-mongo).
 *
 * <p>Tests are skipped automatically when the MongoDB engine is not enabled in floci-az config.
 * They require Docker and FLOCI_AZ_SERVICES_COSMOS_ENGINES_MONGODB_ENABLED=true.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Cosmos MongoDB Engine Compatibility")
class CosmosMongoEngineCompatibilityTest {

    private MongoClient mongoClient;
    private MongoDatabase testDb;
    private static final String DB_NAME = "floci_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    @BeforeAll
    void setup() throws InterruptedException {
        Map<String, Object> engineInfo = EmulatorConfig.triggerCosmosEngine("cosmos-mongo");
        assumeTrue(engineInfo != null, "MongoDB engine not enabled — skipping tests");

        String connectionString = (String) engineInfo.get("connectionString");
        assertNotNull(connectionString, "connectionString must not be null in engine response");

        // Retry connection up to 30s — container may still be starting
        Exception lastException = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                mongoClient = MongoClients.create(connectionString);
                // Ping to verify connection is usable
                mongoClient.getDatabase("admin").runCommand(new Document("ping", 1));
                lastException = null;
                break;
            } catch (Exception e) {
                lastException = e;
                if (mongoClient != null) {
                    try { mongoClient.close(); } catch (Exception ignored) {}
                    mongoClient = null;
                }
                Thread.sleep(2_000);
            }
        }
        if (lastException != null) {
            assumeTrue(false, "MongoDB not ready after 30s: " + lastException.getMessage());
        }

        testDb = mongoClient.getDatabase(DB_NAME);
    }

    @AfterAll
    void teardown() {
        if (testDb != null) {
            testDb.drop();
        }
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    private MongoCollection<Document> collection(String name) {
        return testDb.getCollection(name);
    }

    @Test
    @DisplayName("insertAndFindOne: insert document and retrieve by id")
    void insertAndFindOne() {
        MongoCollection<Document> col = collection("insertAndFindOne");
        Document doc = new Document("_id", "doc-1")
                .append("name", "test")
                .append("value", 42);
        col.insertOne(doc);

        Document found = col.find(Filters.eq("_id", "doc-1")).first();
        assertNotNull(found, "Document should be found");
        assertEquals("test", found.getString("name"));
        assertEquals(42, found.getInteger("value").intValue());
    }

    @Test
    @DisplayName("updateOne: update a field with $set")
    void updateOne() {
        MongoCollection<Document> col = collection("updateOne");
        col.insertOne(new Document("_id", "upd-1").append("status", "draft"));

        col.updateOne(Filters.eq("_id", "upd-1"), Updates.set("status", "published"));

        Document updated = col.find(Filters.eq("_id", "upd-1")).first();
        assertNotNull(updated);
        assertEquals("published", updated.getString("status"));
    }

    @Test
    @DisplayName("deleteOne: insert then delete, verify not found")
    void deleteOne() {
        MongoCollection<Document> col = collection("deleteOne");
        col.insertOne(new Document("_id", "del-1").append("name", "to-be-deleted"));

        col.deleteOne(Filters.eq("_id", "del-1"));

        Document found = col.find(Filters.eq("_id", "del-1")).first();
        assertNull(found, "Document should be deleted");
    }

    @Test
    @DisplayName("findWithFilter: insert 3 docs, find where score > 50")
    void findWithFilter() {
        MongoCollection<Document> col = collection("findWithFilter");
        col.insertOne(new Document("_id", "s-1").append("score", 30));
        col.insertOne(new Document("_id", "s-2").append("score", 60));
        col.insertOne(new Document("_id", "s-3").append("score", 80));

        List<Document> results = new ArrayList<>();
        col.find(Filters.gt("score", 50)).into(results);

        assertEquals(2, results.size());
        List<String> ids = results.stream().map(d -> d.getString("_id")).toList();
        assertTrue(ids.contains("s-2"));
        assertTrue(ids.contains("s-3"));
    }

    @Test
    @DisplayName("countDocuments: insert 5 docs, count equals 5")
    void countDocuments() {
        MongoCollection<Document> col = collection("countDocuments");
        for (int i = 0; i < 5; i++) {
            col.insertOne(new Document("_id", "cnt-" + i).append("index", i));
        }
        assertEquals(5L, col.countDocuments());
    }
}
