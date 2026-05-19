package io.floci.az.compat;

import org.apache.tinkerpop.gremlin.driver.Client;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.Result;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compatibility tests for the Cosmos Gremlin engine (cosmos-gremlin).
 *
 * <p>Tests are skipped automatically when the Gremlin engine is not enabled in floci-az config.
 * They require Docker and FLOCI_AZ_SERVICES_COSMOS_ENGINES_GREMLIN_ENABLED=true.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Cosmos Gremlin Engine Compatibility")
class CosmosGremlinEngineCompatibilityTest {

    private Cluster cluster;
    private Client client;

    @BeforeAll
    void setup() throws InterruptedException {
        Map<String, Object> engineInfo = EmulatorConfig.triggerCosmosEngine("cosmos-gremlin");
        assumeTrue(engineInfo != null, "Gremlin engine not enabled — skipping tests");

        String host = (String) engineInfo.get("host");
        int port = ((Number) engineInfo.get("port")).intValue();

        // Retry connection up to 30s — container may still be starting
        Exception lastException = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Cluster candidate = Cluster.build()
                        .addContactPoint(host)
                        .port(port)
                        .path("/gremlin")
                        .create();
                Client c = candidate.connect();
                // Smoke test
                c.submit("g.V().count()").all().get();
                cluster = candidate;
                client = c;
                lastException = null;
                break;
            } catch (Exception e) {
                lastException = e;
                if (client != null) {
                    try { client.close(); } catch (Exception ignored) {}
                    client = null;
                }
                if (cluster != null) {
                    try { cluster.close(); } catch (Exception ignored) {}
                    cluster = null;
                }
                Thread.sleep(2_000);
            }
        }
        if (lastException != null) {
            assumeTrue(false, "Gremlin server not ready after 30s: " + lastException.getMessage());
        }

        // Clean up any leftover vertices from previous runs
        try {
            client.submit("g.V().drop()").all().get();
        } catch (Exception ignored) {}
    }

    @AfterAll
    void teardown() {
        if (client != null) {
            try {
                client.submit("g.V().drop()").all().get();
            } catch (Exception ignored) {}
            try { client.close(); } catch (Exception ignored) {}
        }
        if (cluster != null) {
            try { cluster.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("addAndGetVertex: add person vertex, query age property")
    void addAndGetVertex() throws Exception {
        client.submit("g.addV('person').property('name','Alice').property('age',30)").all().get();

        List<Result> results = client.submit(
                "g.V().has('person','name','Alice').values('age')").all().get();
        assertFalse(results.isEmpty(), "Should find Alice");
        assertEquals(30, ((Number) results.get(0).getObject()).intValue());
    }

    @Test
    @DisplayName("addEdge: add two vertices, add edge, verify edge exists")
    void addEdge() throws Exception {
        client.submit("g.addV('person').property('name','EdgeSrc').property('tag','edge-test')").all().get();
        client.submit("g.addV('person').property('name','EdgeDst').property('tag','edge-test')").all().get();

        client.submit(
                "g.V().has('name','EdgeSrc').as('src')" +
                ".V().has('name','EdgeDst').as('dst')" +
                ".addE('knows').from('src').to('dst')").all().get();

        List<Result> edges = client.submit(
                "g.V().has('name','EdgeSrc').out('knows').has('name','EdgeDst').count()").all().get();
        assertFalse(edges.isEmpty());
        assertEquals(1, ((Number) edges.get(0).getObject()).intValue());
    }

    @Test
    @DisplayName("dropVertex: add vertex, drop it, verify count = 0")
    void dropVertex() throws Exception {
        client.submit("g.addV('person').property('name','ToDelete')").all().get();

        client.submit("g.V().has('name','ToDelete').drop()").all().get();

        List<Result> results = client.submit(
                "g.V().has('name','ToDelete').count()").all().get();
        assertFalse(results.isEmpty());
        assertEquals(0, ((Number) results.get(0).getObject()).intValue());
    }

    @Test
    @DisplayName("countVertices: g.V().count() returns a non-negative number")
    void countVertices() throws Exception {
        List<Result> results = client.submit("g.V().count()").all().get();
        assertFalse(results.isEmpty());
        long count = ((Number) results.get(0).getObject()).longValue();
        assertTrue(count >= 0, "Vertex count should be >= 0");
    }
}
