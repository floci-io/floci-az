package io.floci.az.services.cosmos;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the ORDER BY clause of {@link CosmosQueryEngine#prepare} —
 * the parse feeding the composite-index validation must see the ORDER BY
 * clause exactly as the execution path does, across casing, whitespace, and
 * clause boundaries.
 */
class CosmosQueryEngineOrderByTest {

    private final CosmosQueryEngine engine = new CosmosQueryEngine();

    private List<CosmosQueryEngine.OrderByField> parseOrderBy(String sql) {
        return engine.prepare(sql, List.of()).orderBy();
    }

    @Test
    void noOrderByYieldsEmptyList() {
        assertTrue(parseOrderBy("SELECT * FROM c WHERE c.a = 1").isEmpty());
    }

    @Test
    void singleFieldDefaultsToAscending() {
        List<CosmosQueryEngine.OrderByField> ob = parseOrderBy("SELECT * FROM c ORDER BY c.a");
        assertEquals(1, ob.size());
        assertEquals("c.a", ob.get(0).path());
        assertTrue(ob.get(0).asc());
    }

    @Test
    void explicitDirectionsParsed() {
        List<CosmosQueryEngine.OrderByField> ob =
                parseOrderBy("SELECT * FROM c ORDER BY c.a ASC, c.b DESC");
        assertEquals(2, ob.size());
        assertTrue(ob.get(0).asc());
        assertFalse(ob.get(1).asc());
    }

    @Test
    void fieldSequencePreserved() {
        List<CosmosQueryEngine.OrderByField> ob =
                parseOrderBy("SELECT * FROM c ORDER BY c.b, c.a, c.z");
        assertEquals(List.of("c.b", "c.a", "c.z"),
                ob.stream().map(CosmosQueryEngine.OrderByField::path).toList());
    }

    @Test
    void clauseEndsAtOffset() {
        List<CosmosQueryEngine.OrderByField> ob =
                parseOrderBy("SELECT * FROM c ORDER BY c.a, c.b OFFSET 5 LIMIT 10");
        assertEquals(2, ob.size());
        assertEquals("c.b", ob.get(1).path());
    }

    @Test
    void lowercaseKeywordsParsed() {
        List<CosmosQueryEngine.OrderByField> ob =
                parseOrderBy("select * from c order by c.a desc, c.b");
        assertEquals(2, ob.size());
        assertFalse(ob.get(0).asc());
        assertTrue(ob.get(1).asc());
    }

    @Test
    void extraWhitespaceAndNewlinesNormalized() {
        List<CosmosQueryEngine.OrderByField> ob =
                parseOrderBy("SELECT *\n  FROM c\n  ORDER BY   c.a ,\n  c.b   DESC");
        assertEquals(2, ob.size());
        assertEquals("c.a", ob.get(0).path());
        assertEquals("c.b", ob.get(1).path());
        assertFalse(ob.get(1).asc());
    }

    @Test
    void whereClauseDoesNotLeakIntoOrderBy() {
        List<CosmosQueryEngine.OrderByField> ob = parseOrderBy(
                "SELECT * FROM c WHERE c.category = 'order by trap' ORDER BY c.a, c.b");
        assertEquals(2, ob.size());
        assertEquals("c.a", ob.get(0).path());
    }

    @Test
    void paramValueWithApostropheDoesNotSwallowOrderBy() {
        // "Alice's" substitutes to a literal containing an escaped quote. A
        // scanner that mishandles the escape stays in string mode through the
        // ORDER BY clause, yielding an empty list and silently skipping both
        // composite-index enforcement and sorting.
        List<CosmosQueryEngine.OrderByField> ob = engine.prepare(
                "SELECT * FROM c WHERE c.name = @name ORDER BY c.conversationId, c.sequence",
                List.of(Map.of("name", "@name", "value", "Alice's"))).orderBy();
        assertEquals(2, ob.size());
        assertEquals("c.conversationId", ob.get(0).path());
        assertEquals("c.sequence", ob.get(1).path());
    }

    @Test
    void paramValueWithApostropheFiltersAndSortsCorrectly() {
        List<Map<String, Object>> docs = List.of(
                Map.of("id", "a", "name", "Alice's", "seq", 2),
                Map.of("id", "b", "name", "Alice's", "seq", 1),
                Map.of("id", "c", "name", "Bob", "seq", 9));
        CosmosQueryEngine.QueryResult r = engine.execute(
                "SELECT * FROM c WHERE c.name = @name ORDER BY c.seq",
                List.of(Map.of("name", "@name", "value", "Alice's")), docs);
        // Equality must match the un-escaped value, and the two matches sort by seq.
        assertEquals(List.of("b", "a"),
                r.items().stream().map(d -> ((Map<?, ?>) d).get("id")).toList());
    }
}
