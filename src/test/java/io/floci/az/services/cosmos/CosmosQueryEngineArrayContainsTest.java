package io.floci.az.services.cosmos;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CosmosQueryEngineArrayContainsTest {
    private final CosmosQueryEngine engine = new CosmosQueryEngine();

    @Test
    void parameterArrayMatchesFieldAndNegationExcludesIt() {
        var docs = List.of(Map.<String, Object>of("id", "alice"), Map.<String, Object>of("id", "bob"));
        var params = List.of(Map.<String, Object>of("name", "@ids", "value", List.of("alice")));
        assertEquals(List.of("alice"), engine.execute(
                "SELECT VALUE c.id FROM c WHERE ARRAY_CONTAINS(@ids, c.id)", params, docs).items());
        assertEquals(List.of("bob"), engine.execute(
                "SELECT VALUE c.id FROM c WHERE NOT ARRAY_CONTAINS(@ids, c.id)", params, docs).items());
    }

    @Test
    void preservesArrayElementTypesAndEmptyArrays() {
        var docs = List.of(Map.<String, Object>of("value", 1), Map.<String, Object>of("value", "1"),
                Map.<String, Object>of("value", true));
        assertEquals(List.of(1, true), engine.execute(
                "SELECT VALUE c.value FROM c WHERE ARRAY_CONTAINS(@values, c.value)",
                List.of(Map.of("name", "@values", "value", List.of(1.0, true))), docs).items());
        assertEquals(List.of(), engine.execute(
                "SELECT * FROM c WHERE ARRAY_CONTAINS(@values, c.value)",
                List.of(Map.of("name", "@values", "value", List.of())), docs).items());
        assertEquals(List.of("one"), engine.execute(
                "SELECT VALUE c.id FROM c WHERE ARRAY_CONTAINS(@values, null)",
                List.of(Map.of("name", "@values", "value", Arrays.asList((Object) null))),
                List.of(Map.of("id", "one"))).items());
    }

    @Test
    void handlesQuotedStringsWhitespaceAndParameterNamesInsideValues() {
        var values = List.of("comma,value", "Alice's", "two  spaces", "@ids", "a\"quote", "AND OR ORDER BY");
        var docs = values.stream().map(value -> Map.<String, Object>of("id", value)).toList();
        assertEquals(values, engine.execute(
                "SELECT VALUE c.id FROM c WHERE ARRAY_CONTAINS(@idsLong, c.id)",
                List.of(Map.of("name", "@ids", "value", List.of("wrong")),
                        Map.of("name", "@idsLong", "value", values)), docs).items());
    }

    @Test
    void supportsDocumentArraysLiteralArraysAndFunctionArguments() {
        var docs = List.of(Map.<String, Object>of("id", "alice", "ids", List.of("alice", "bob")));
        for (String predicate : List.of("ARRAY_CONTAINS(c.ids, c.id)",
                "ARRAY_CONTAINS(['bob', 'alice'], c.id)",
                "ARRAY_CONTAINS(c.ids, 'alice')", "ARRAY_CONTAINS(['ALICE'], UPPER(c.id))")) {
            assertEquals(docs, engine.execute("SELECT * FROM c WHERE " + predicate, List.of(), docs).items());
        }
    }
}
