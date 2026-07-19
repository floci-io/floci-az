package io.floci.az.services.cosmos;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CosmosTtl} — the Azure TTL semantics matrix from
 * https://learn.microsoft.com/en-us/azure/cosmos-db/time-to-live, evaluated
 * with an explicit clock so no test needs to sleep.
 */
class CosmosTtlTest {

    private static final long TS  = 1_000_000L;

    private static Map<String, Object> doc(Object ttl) {
        Map<String, Object> d = new HashMap<>();
        d.put("id",  "doc1");
        d.put("_ts", TS);
        if (ttl != null) d.put("ttl", ttl);
        return d;
    }

    // --- Example 1: container TTL absent — TTL disabled ---

    @Test
    void nothingExpiresWhenContainerTtlAbsent() {
        long farFuture = TS + 1_000_000;
        assertFalse(CosmosTtl.isExpired(doc(null), null, farFuture), "no item ttl");
        assertFalse(CosmosTtl.isExpired(doc(-1),   null, farFuture), "item ttl -1");
        assertFalse(CosmosTtl.isExpired(doc(2000), null, farFuture), "item ttl 2000 is inert when TTL is off");
    }

    // --- Example 2: container TTL -1 — enabled, no default expiry ---

    @Test
    void containerMinusOneExpiresOnlyItemsWithPositiveTtl() {
        long afterItemTtl = TS + 2001;
        assertFalse(CosmosTtl.isExpired(doc(null), -1, afterItemTtl), "no item ttl — never expires");
        assertFalse(CosmosTtl.isExpired(doc(-1),   -1, afterItemTtl), "item ttl -1 — never expires");
        assertTrue(CosmosTtl.isExpired(doc(2000),  -1, afterItemTtl), "item ttl 2000 — expires");
        assertFalse(CosmosTtl.isExpired(doc(2000), -1, TS + 1999),   "item ttl 2000 — not yet expired");
    }

    // --- Example 3: container TTL n — default expiry, item override ---

    @Test
    void containerDefaultAppliesUnlessItemOverrides() {
        int defaultTtl = 1000;
        assertTrue(CosmosTtl.isExpired(doc(null), defaultTtl, TS + 1000), "default applies");
        assertFalse(CosmosTtl.isExpired(doc(null), defaultTtl, TS + 999), "default not yet reached");
        assertFalse(CosmosTtl.isExpired(doc(-1),   defaultTtl, TS + 999_999), "item -1 overrides to never");
        assertTrue(CosmosTtl.isExpired(doc(2000),  defaultTtl, TS + 2000), "item 2000 overrides default");
        assertFalse(CosmosTtl.isExpired(doc(2000), defaultTtl, TS + 1500), "longer override outlives default");
    }

    @Test
    void expiryBoundaryIsAtTsPlusTtl() {
        assertFalse(CosmosTtl.isExpired(doc(null), 60, TS + 59));
        assertTrue(CosmosTtl.isExpired(doc(null),  60, TS + 60));
    }

    // --- Defensive handling of odd stored values ---

    @Test
    void malformedItemTtlFallsBackToContainerDefault() {
        assertTrue(CosmosTtl.isExpired(doc("60"), 10, TS + 10), "string ttl ignored — default applies");
        assertTrue(CosmosTtl.isExpired(doc(1.5),  10, TS + 10), "fractional ttl ignored — default applies");
    }

    @Test
    void nonPositiveItemTtlMeansNeverExpires() {
        assertFalse(CosmosTtl.isExpired(doc(0),  10, TS + 999_999));
        assertFalse(CosmosTtl.isExpired(doc(-5), 10, TS + 999_999));
    }

    @Test
    void missingTsNeverExpires() {
        Map<String, Object> noTs = new HashMap<>(Map.of("id", "doc1"));
        assertFalse(CosmosTtl.isExpired(noTs, 10, Long.MAX_VALUE));
    }

    @Test
    void longValuesFromJsonAreAccepted() {
        Map<String, Object> d = doc(2000L);       // Jackson may parse numbers as Long
        d.put("_ts", (int) TS);                   // ... or Integer
        assertTrue(CosmosTtl.isExpired(d, -1L, TS + 2000));
    }

    // --- normalizeDefaultTtl ---

    @Test
    void normalizeAcceptsAbsentMinusOneAndPositive() {
        assertNull(CosmosTtl.normalizeDefaultTtl(null));
        assertEquals(-1,   CosmosTtl.normalizeDefaultTtl(-1));
        assertEquals(1,    CosmosTtl.normalizeDefaultTtl(1));
        assertEquals(3600, CosmosTtl.normalizeDefaultTtl(3600));
        assertEquals(Integer.MAX_VALUE, CosmosTtl.normalizeDefaultTtl(2147483647L));
        assertEquals(60,   CosmosTtl.normalizeDefaultTtl(60.0), "whole-number double is accepted");
    }

    @Test
    void normalizeRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> CosmosTtl.normalizeDefaultTtl(0));
        assertThrows(IllegalArgumentException.class, () -> CosmosTtl.normalizeDefaultTtl(-2));
        assertThrows(IllegalArgumentException.class, () -> CosmosTtl.normalizeDefaultTtl(2147483648L));
        assertThrows(IllegalArgumentException.class, () -> CosmosTtl.normalizeDefaultTtl(1.5));
        assertThrows(IllegalArgumentException.class, () -> CosmosTtl.normalizeDefaultTtl("3600"));
        assertThrows(IllegalArgumentException.class, () -> CosmosTtl.normalizeDefaultTtl(Map.of()));
    }
}
