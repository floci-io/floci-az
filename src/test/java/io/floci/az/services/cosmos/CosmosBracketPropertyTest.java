package io.floci.az.services.cosmos;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CosmosBracketPropertyTest {
    private final CosmosQueryEngine engine = new CosmosQueryEngine();

    @Test
    void erasureQueryFindsUsersPresentOnlyInTheRsvpVersionMap() {
        String userId = "eec725e5-b24c-48c1-b336-e599da1e6711";
        var documents = List.of(
                Map.<String, Object>of("id", "declined", "goingUserIds", List.of(),
                        "rsvpVersionsByUserId", Map.of(userId, 4)),
                Map.<String, Object>of("id", "going", "goingUserIds", List.of(userId)),
                Map.<String, Object>of("id", "unrelated", "rsvpVersionsByUserId", Map.of("other-user", 2)));

        assertEquals(List.of("declined", "going"), engine.execute(
                "SELECT VALUE c.id FROM c WHERE ARRAY_CONTAINS(c.goingUserIds, @userId) "
                        + "OR IS_DEFINED(c.rsvpVersionsByUserId[\"" + userId + "\"])",
                List.of(Map.of("name", "@userId", "value", userId)), documents).items());
    }

    @Test
    void bracketMembersPreserveDotsSpacesAndEmptyKeys() {
        var document = Map.<String, Object>of("map", Map.of("a.b", Map.of("", 7), "two  spaces", 8));
        assertEquals(7, engine.resolve(document, "c[\"map\"][\"a.b\"][\"\"]"));
        assertEquals(7, engine.resolve(document, "c.map['a.b']['']"));
        assertEquals(List.of(8), engine.execute(
                "SELECT VALUE c.map[\"two  spaces\"] FROM c", List.of(), List.of(document)).items());
    }

    @Test
    void mixedMemberAccessWorksInPredicatesAndProjections() {
        var documents = List.of(Map.<String, Object>of("map", Map.of("user-id", Map.of("version", 4))));
        assertEquals(List.of(4), engine.execute(
                "SELECT VALUE c[\"map\"]['user-id'].version FROM c "
                        + "WHERE c.map[\"user-id\"].version = 4", List.of(), documents).items());
        assertEquals(List.of(), engine.execute(
                "SELECT * FROM c WHERE IS_DEFINED(c.map[\"missing\"])", List.of(), documents).items());
    }

    @Test
    void bracketStringsDecodeEscapesWithoutSplittingTheMember() {
        var document = Map.<String, Object>of("map", Map.of("a\"b", 1, "a\\b", 2, "Alice's", 3));
        assertEquals(1, engine.resolve(document, "c.map[\"a\\\"b\"]"));
        assertEquals(2, engine.resolve(document, "c.map[\"a\\\\b\"]"));
        assertEquals(3, engine.resolve(document, "c.map['Alice''s']"));
        assertNull(engine.resolve(document, "c.map[\"missing\"].value"));
        assertNull(engine.resolve(document, "c.map[\"a\"b\"]"));
    }
}
