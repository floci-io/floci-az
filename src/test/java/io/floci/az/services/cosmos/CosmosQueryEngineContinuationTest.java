package io.floci.az.services.cosmos;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CosmosQueryEngineContinuationTest {
    private final CosmosQueryEngine engine = new CosmosQueryEngine();

    @ParameterizedTest
    @ValueSource(strings = {"SELECT VALUE c.id FROM c", "SELECT VALUE c.id FROM c ORDER BY c.rank"})
    void legacyTokensPreserveOriginalOrderUntilCompletion(String sql) {
        List<Map<String, Object>> documents = List.of(
                Map.of("id", "first", "_rid", "z", "rank", 1),
                Map.of("id", "second", "_rid", "y", "rank", 1),
                Map.of("id", "third", "_rid", "x", "rank", 1));
        var query = engine.prepare(sql, List.of());
        var legacy = new CosmosQueryEngine.QueryContinuation(1, null, List.of());

        var second = engine.executePage(query, documents, legacy, 1);
        assertEquals(List.of("second"), second.result().items());
        assertNotNull(second.continuation());

        var third = engine.executePage(query, documents, second.continuation(), 1);
        assertEquals(List.of("third"), third.result().items());
        assertNull(third.continuation());
    }
}
