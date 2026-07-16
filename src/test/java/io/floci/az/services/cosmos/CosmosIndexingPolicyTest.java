package io.floci.az.services.cosmos;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CosmosIndexingPolicy}: normalization of
 * client-supplied policies and the composite-index ORDER BY matching rule.
 */
class CosmosIndexingPolicyTest {

    private static CosmosQueryEngine.OrderByField ob(String path, boolean asc) {
        return new CosmosQueryEngine.OrderByField(path, asc);
    }

    private static Map<String, Object> policyWithComposite(List<Map<String, Object>> paths) {
        return CosmosIndexingPolicy.normalize(Map.of("compositeIndexes", List.of(paths)));
    }

    private static Map<String, Object> path(String p, String order) {
        return order == null ? Map.of("path", p) : Map.of("path", p, "order", order);
    }

    // ------------------------------------------------------------------ normalize

    @Test
    void missingPolicyYieldsDefault() {
        Map<String, Object> p = CosmosIndexingPolicy.normalize(null);
        assertEquals(true, p.get("automatic"));
        assertEquals("consistent", p.get("indexingMode"));
        assertEquals(List.of(Map.of("path", "/*")), p.get("includedPaths"));
        assertFalse(p.containsKey("compositeIndexes"));
    }

    @Test
    void compositeOrderDefaultsToAscending() {
        Map<String, Object> p = policyWithComposite(List.of(path("/a", null), path("/b", "descending")));
        @SuppressWarnings("unchecked")
        List<List<Map<String, Object>>> composites =
                (List<List<Map<String, Object>>>) p.get("compositeIndexes");
        assertEquals("ascending",  composites.get(0).get(0).get("order"));
        assertEquals("descending", composites.get(0).get(1).get("order"));
    }

    @Test
    void indexingModeIsLowercased() {
        Map<String, Object> p = CosmosIndexingPolicy.normalize(Map.of("indexingMode", "Consistent"));
        assertEquals("consistent", p.get("indexingMode"));
    }

    @Test
    void invalidIndexingModeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CosmosIndexingPolicy.normalize(Map.of("indexingMode", "bogus")));
    }

    @Test
    void singlePathCompositeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CosmosIndexingPolicy.normalize(
                        Map.of("compositeIndexes", List.of(List.of(path("/a", null))))));
    }

    @Test
    void compositePathWithoutLeadingSlashRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CosmosIndexingPolicy.normalize(
                        Map.of("compositeIndexes", List.of(List.of(path("a", null), path("/b", null))))));
    }

    // ------------------------------------------------------------------ supportsOrderBy

    @Test
    void singlePropertyOrderByAlwaysSupported() {
        Map<String, Object> p = CosmosIndexingPolicy.defaultPolicy();
        assertTrue(CosmosIndexingPolicy.supportsOrderBy(p, List.of(ob("c.a", true))));
        assertTrue(CosmosIndexingPolicy.supportsOrderBy(p, List.of(ob("c.a", false))));
    }

    @Test
    void multiPropertyOrderByWithoutCompositeIndexRejected() {
        assertFalse(CosmosIndexingPolicy.supportsOrderBy(
                CosmosIndexingPolicy.defaultPolicy(), List.of(ob("c.a", true), ob("c.b", true))));
    }

    @Test
    void exactMatchSupported() {
        Map<String, Object> p = policyWithComposite(List.of(path("/a", "ascending"), path("/b", "ascending")));
        assertTrue(CosmosIndexingPolicy.supportsOrderBy(p, List.of(ob("c.a", true), ob("c.b", true))));
    }

    @Test
    void fullyInvertedMatchSupported() {
        Map<String, Object> p = policyWithComposite(List.of(path("/a", "ascending"), path("/b", "descending")));
        assertTrue(CosmosIndexingPolicy.supportsOrderBy(p, List.of(ob("c.a", false), ob("c.b", true))));
    }

    @Test
    void partiallyInvertedMatchRejected() {
        Map<String, Object> p = policyWithComposite(List.of(path("/a", "ascending"), path("/b", "ascending")));
        assertFalse(CosmosIndexingPolicy.supportsOrderBy(p, List.of(ob("c.a", true), ob("c.b", false))));
    }

    @Test
    void wrongSequenceRejected() {
        Map<String, Object> p = policyWithComposite(List.of(path("/a", "ascending"), path("/b", "ascending")));
        assertFalse(CosmosIndexingPolicy.supportsOrderBy(p, List.of(ob("c.b", true), ob("c.a", true))));
    }

    @Test
    void orderByPrefixOfLongerCompositeRejected() {
        // Azure: (a, b, c) does NOT serve ORDER BY a, b — length must match exactly
        Map<String, Object> p = policyWithComposite(
                List.of(path("/a", "ascending"), path("/b", "ascending"), path("/c", "ascending")));
        assertFalse(CosmosIndexingPolicy.supportsOrderBy(p, List.of(ob("c.a", true), ob("c.b", true))));
    }

    @Test
    void nestedPathsAndQuotedSegmentsMatch() {
        Map<String, Object> p = policyWithComposite(
                List.of(path("/meta/\"created-at\"", "ascending"), path("/meta/seq", "ascending")));
        assertTrue(CosmosIndexingPolicy.supportsOrderBy(p,
                List.of(ob("c.meta.created-at", true), ob("c.meta.seq", true))));
    }
}
