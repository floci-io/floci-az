package io.floci.az.services.cosmos;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CosmosQueryEngineContinuationTest {
    private final CosmosQueryEngine engine = new CosmosQueryEngine();

    /** A document written by an older build, or stripped by a patch, carries no {@code _rid}. */
    private static final List<Map<String, Object>> WITH_ONE_RIDLESS = List.of(
            Map.of("id", "first", "_rid", "z", "rank", 1),
            Map.of("id", "ridless", "rank", 2),
            Map.of("id", "third", "_rid", "x", "rank", 3));

    @Test
    void documentsWithoutStoredIdentityFallBackToOffsetPaging() {
        var query = engine.prepare("SELECT VALUE c.id FROM c", List.of());

        List<Object> seen = new ArrayList<>();
        CosmosQueryEngine.QueryContinuation token = null;
        for (int page = 0; page < 3; page++) {
            var result = engine.executePage(query, WITH_ONE_RIDLESS, token, 1);
            seen.addAll(result.result().items());
            token = result.continuation();
            if (page < 2) {
                assertNotNull(token, "a page boundary must still produce a continuation");
                assertNull(token.rid(), "a rid bookmark cannot address a document without _rid");
            }
        }
        assertNull(token, "the last page ends the query");
        assertEquals(List.of("first", "ridless", "third"), seen);
    }

    @Test
    void onePoisonedDocumentDoesNotBreakAFilteredQuery() {
        // The identity check used to run over every scoped document before the WHERE filter, so a single
        // document without _rid broke every query in the container, even one that excluded it.
        var query = engine.prepare("SELECT VALUE c.id FROM c WHERE c.rank > 2", List.of());

        var page = engine.executePage(query, WITH_ONE_RIDLESS, null, 10);

        assertEquals(List.of("third"), page.result().items());
        assertNull(page.continuation());
    }


    /** The document without {@code _rid} is the one the predicate below excludes. */
    private static final List<Map<String, Object>> POISON_EXCLUDED_BY_FILTER = List.of(
            Map.of("id", "keep-1", "_rid", "z", "rank", 3),
            Map.of("id", "poison", "rank", 1),
            Map.of("id", "keep-2", "_rid", "x", "rank", 5));

    @Test
    void aRidTokenResumesWhenTheFilterExcludesThePoisonedDocument() {
        // A document the WHERE clause removes never reaches the comparator, so it cannot have
        // influenced the order the bookmark was issued against. Refusing the token because such a
        // document exists somewhere in the container restarts a query that was never at risk.
        var query = engine.prepare("SELECT VALUE c.id FROM c WHERE c.rank > 2", List.of());
        var ridToken = new CosmosQueryEngine.QueryContinuation(1, "x", List.of());

        var page = engine.executePage(query, POISON_EXCLUDED_BY_FILTER, ridToken, 10);

        assertEquals(List.of("keep-1"), page.result().items());
        assertNull(page.continuation());
    }

    @Test
    void aRidTokenIsNotReinterpretedAsAnOffset() {
        // Pages 1..n were produced in rid order. If a document later loses its _rid, resuming that
        // bookmark as a plain offset indexes a differently ordered list, which skips or repeats
        // documents. Surface it instead of returning a silently wrong page.
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
