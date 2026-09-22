package io.floci.az.services.cosmos;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CosmosQueryEngineContinuationTest {
    private final CosmosQueryEngine engine = new CosmosQueryEngine();

    @Test
    void missingStoredIdentityHasClearDiagnostic() {
        var query = engine.prepare("SELECT c.id FROM c", List.of());
        NullPointerException error = assertThrows(NullPointerException.class,
                () -> engine.executePage(query, List.of(Map.of("id", "missing")), null, 1));
        assertEquals("Stored Cosmos document is missing _rid required for query continuation", error.getMessage());
    }

    @Test
    void aRidTokenIsNotReinterpretedAsAnOffset() {
        // Pages 1..n were produced in rid order. If a document later loses its _rid, resuming the
        // rid bookmark as a plain offset indexes a differently ordered list, which skips or repeats
        // documents. Surface that rather than return a silently wrong page.
        var query = engine.prepare("SELECT VALUE c.id FROM c", List.of());
        var ridToken = new CosmosQueryEngine.QueryContinuation(1, "z", List.of());

        assertThrows(IllegalArgumentException.class,
                () -> engine.executePage(query, WITH_ONE_RIDLESS, ridToken, 10));
    }

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
