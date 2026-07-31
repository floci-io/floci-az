package io.floci.az.services.cosmos;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CosmosQueryEngineExistsTest {

    @Test
    void evaluatesCorrelatedExistsOverNestedArray() {
        CosmosQueryEngine engine = new CosmosQueryEngine();
        List<Map<String, Object>> documents = List.of(
                Map.of("id", "matching", "actions", List.of(
                        Map.of("resolvesOn", Map.of("type", "rsvp")))),
                Map.of("id", "non-matching", "actions", List.of(
                        Map.of("resolvesOn", Map.of("type", "comment")))),
                Map.of("id", "empty", "actions", List.of()));

        CosmosQueryEngine.QueryResult result = engine.execute(
                "SELECT * FROM c WHERE EXISTS("
                        + "SELECT VALUE action FROM action IN c.actions "
                        + "WHERE action.resolvesOn.type = @type)",
                List.of(Map.of("name", "@type", "value", "rsvp")),
                documents);

        assertEquals(List.of("matching"), result.items().stream()
                .map(item -> ((Map<?, ?>) item).get("id"))
                .toList());
    }
}
