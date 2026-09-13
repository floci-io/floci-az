package io.floci.az.services.cosmos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CosmosQueryEngineLogicalPrecedenceTest {

    private final CosmosQueryEngine engine = new CosmosQueryEngine();

    @ParameterizedTest
    @CsvSource({"0, false", "1, true", "2, true", "3, false"})
    void keepsBetweenRangeSeparateFromLogicalAnd(int score, boolean withinRange) {
        Map<String, Object> document = Map.of("score", score, "active", true);

        assertEquals(withinRange, engine.evalExpr(document, "c.score BETWEEN 1 AND 2"));
        assertEquals(!withinRange, engine.evalExpr(document, "NOT c.score BETWEEN 1 AND 2"));
        assertEquals(!withinRange, engine.evalExpr(document, "NOT (c.score BETWEEN 1 AND 2)"));
        assertEquals(!withinRange,
                engine.evalExpr(document, "NOT c.score BETWEEN 1 AND 2 AND c.active = true"));
        assertEquals(!withinRange,
                engine.evalExpr(document, "c.active = true AND NOT c.score BETWEEN 1 AND 2"));
        assertEquals(true, engine.evalExpr(document, "NOT c.score BETWEEN 1 AND 2 OR c.active = true"));
        assertEquals(withinRange,
                engine.evalExpr(document, "c.score BETWEEN 1 AND 2 AND c.score BETWEEN 0 AND 3"));
        assertEquals(!withinRange, engine.evalExpr(document, "not c.score between 1 and 2"));
    }

    @Test
    void ignoresBetweenInsideQuotedStringsAndNestedExpressions() {
        Map<String, Object> document = Map.of("text", "BETWEEN AND", "score", 3);
        assertEquals(true, engine.evalExpr(document,
                "c.text = 'BETWEEN AND' AND NOT c.score BETWEEN 1 AND 2"));
        assertEquals(false, engine.evalExpr(document,
                "(c.score BETWEEN 1 AND 2) AND c.text = 'BETWEEN AND'"));
    }

    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void respectsLogicalPrecedence(boolean a, boolean b) {
        Map<String, Object> document = Map.of("a", a, "b", b);

        assertEquals(!a || b, engine.evalExpr(document, "NOT c.a = true OR c.b = true"));
        assertEquals(!a && b, engine.evalExpr(document, "NOT c.a = true AND c.b = true"));
        assertEquals(!(a || b), engine.evalExpr(document, "NOT (c.a = true OR c.b = true)"));
        assertEquals(!(a && b), engine.evalExpr(document, "NOT (c.a = true AND c.b = true)"));
        assertEquals(!a || b, engine.evalExpr(document, "((NOT c.a = true) OR (c.b = true))"));
        assertEquals(a || b, engine.evalExpr(document, "NOT NOT c.a = true OR c.b = true"));
        assertEquals(!a || b && a,
                engine.evalExpr(document, "NOT c.a = true OR c.b = true AND c.a = true"));
        assertEquals((!a || b) && a,
                engine.evalExpr(document, "((NOT c.a = true OR c.b = true) AND c.a = true)"));
        assertEquals(!a || b, engine.evalExpr(document, "not c.a = true or c.b = true"));
    }

    @Test
    void includesNonDeletedDocuments() {
        List<Map<String, Object>> documents = List.of(
                Map.of("id", "absent"),
                Map.of("id", "false", "isDeleted", false),
                Map.of("id", "true", "isDeleted", true));

        assertEquals(List.of("absent", "false"), engine.execute(
                "SELECT VALUE c.id FROM c WHERE (NOT IS_DEFINED(c.isDeleted) OR c.isDeleted = false)",
                List.of(), documents).items());
    }

    @Test
    void includesAbsentAndNullClaims() {
        Map<String, Object> nullClaim = new LinkedHashMap<>();
        nullClaim.put("id", "null");
        nullClaim.put("claimedUntil", null);
        List<Map<String, Object>> documents = List.of(
                Map.of("id", "absent"), nullClaim,
                Map.of("id", "claimed", "claimedUntil", "2026-09-12T12:00:00Z"),
                Map.of("id", "false", "claimedUntil", false),
                Map.of("id", "true", "claimedUntil", true));

        assertEquals(List.of("absent", "null"), engine.execute(
                "SELECT VALUE c.id FROM c WHERE (NOT IS_DEFINED(c.claimedUntil) OR IS_NULL(c.claimedUntil))",
                List.of(), documents).items());
    }
}
