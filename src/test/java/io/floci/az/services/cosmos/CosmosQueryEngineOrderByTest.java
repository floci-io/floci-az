package io.floci.az.services.cosmos;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CosmosQueryEngine#parseOrderBy} — the parse feeding
 * the composite-index validation must see the ORDER BY clause exactly as the
 * execution path does, across casing, whitespace, and clause boundaries.
 */
class CosmosQueryEngineOrderByTest {

    private final CosmosQueryEngine engine = new CosmosQueryEngine();

    @Test
    void noOrderByYieldsEmptyList() {
        assertTrue(engine.parseOrderBy("SELECT * FROM c WHERE c.a = 1").isEmpty());
    }

    @Test
    void singleFieldDefaultsToAscending() {
        List<CosmosQueryEngine.OrderByField> ob = engine.parseOrderBy("SELECT * FROM c ORDER BY c.a");
        assertEquals(1, ob.size());
        assertEquals("c.a", ob.get(0).path());
        assertTrue(ob.get(0).asc());
    }

    @Test
    void explicitDirectionsParsed() {
        List<CosmosQueryEngine.OrderByField> ob =
                engine.parseOrderBy("SELECT * FROM c ORDER BY c.a ASC, c.b DESC");
        assertEquals(2, ob.size());
        assertTrue(ob.get(0).asc());
        assertFalse(ob.get(1).asc());
    }

    @Test
    void fieldSequencePreserved() {
        List<CosmosQueryEngine.OrderByField> ob =
                engine.parseOrderBy("SELECT * FROM c ORDER BY c.b, c.a, c.z");
        assertEquals(List.of("c.b", "c.a", "c.z"),
                ob.stream().map(CosmosQueryEngine.OrderByField::path).toList());
    }

    @Test
    void clauseEndsAtOffset() {
        List<CosmosQueryEngine.OrderByField> ob =
                engine.parseOrderBy("SELECT * FROM c ORDER BY c.a, c.b OFFSET 5 LIMIT 10");
        assertEquals(2, ob.size());
        assertEquals("c.b", ob.get(1).path());
    }

    @Test
    void lowercaseKeywordsParsed() {
        List<CosmosQueryEngine.OrderByField> ob =
                engine.parseOrderBy("select * from c order by c.a desc, c.b");
        assertEquals(2, ob.size());
        assertFalse(ob.get(0).asc());
        assertTrue(ob.get(1).asc());
    }

    @Test
    void extraWhitespaceAndNewlinesNormalized() {
        List<CosmosQueryEngine.OrderByField> ob =
                engine.parseOrderBy("SELECT *\n  FROM c\n  ORDER BY   c.a ,\n  c.b   DESC");
        assertEquals(2, ob.size());
        assertEquals("c.a", ob.get(0).path());
        assertEquals("c.b", ob.get(1).path());
        assertFalse(ob.get(1).asc());
    }

    @Test
    void whereClauseDoesNotLeakIntoOrderBy() {
        List<CosmosQueryEngine.OrderByField> ob = engine.parseOrderBy(
                "SELECT * FROM c WHERE c.category = 'order by trap' ORDER BY c.a, c.b");
        assertEquals(2, ob.size());
        assertEquals("c.a", ob.get(0).path());
    }
}
