package io.floci.az.services.cosmos;

import java.util.Map;

/**
 * Pure TTL (time-to-live) decision logic for the Cosmos DB emulator.
 *
 * <p>Azure semantics
 * (<a href="https://learn.microsoft.com/en-us/azure/cosmos-db/time-to-live">docs</a>):</p>
 * <ul>
 *   <li>Container {@code defaultTtl} absent (or null) — TTL is disabled; no item
 *       expires, even when the item carries its own {@code ttl} property.</li>
 *   <li>Container {@code defaultTtl = -1} — TTL is enabled but items do not expire
 *       by default; only items with a positive {@code ttl} of their own expire.</li>
 *   <li>Container {@code defaultTtl = n > 0} — items expire {@code n} seconds after
 *       their last modification ({@code _ts}) unless overridden per item.</li>
 *   <li>Item {@code ttl = m > 0} — the item expires {@code m} seconds after
 *       {@code _ts}; {@code ttl = -1} — the item never expires.</li>
 * </ul>
 */
final class CosmosTtl {

    /** Azure's maximum TTL: 2147483647 seconds (~68 years). */
    private static final long MAX_TTL = Integer.MAX_VALUE;

    private CosmosTtl() {}

    /**
     * Validates the {@code defaultTtl} property of a container create/replace body.
     *
     * @return the normalised value, or {@code null} when the property is absent
     *         (TTL disabled)
     * @throws IllegalArgumentException when the value is not {@code -1} or a
     *         positive integer up to 2147483647
     */
    static Integer normalizeDefaultTtl(Object raw) {
        if (raw == null) return null;
        Long value = asIntegral(raw);
        if (value == null || (value != -1 && (value < 1 || value > MAX_TTL))) {
            throw new IllegalArgumentException(
                    "The value '" + raw + "' specified for 'defaultTtl' is invalid. "
                    + "It must be a nonzero positive integer up to 2147483647, "
                    + "or -1 so that items do not expire by default.");
        }
        return value.intValue();
    }

    /**
     * Decides whether a document is expired at {@code nowEpochSecond}.
     *
     * <p>{@code defaultTtlRaw} is the container's stored {@code defaultTtl} value
     * (may be {@code null}). An item {@code ttl} that is a whole number overrides
     * the container default: a positive value expires the item that many seconds
     * after {@code _ts}, while {@code -1} (and, defensively, any other value
     * {@code <= 0}) means the item never expires. A malformed item {@code ttl}
     * (non-numeric or fractional) is treated as absent, so the container default
     * applies — Azure would have rejected such a document at write time.</p>
     */
    static boolean isExpired(Map<String, Object> doc, Object defaultTtlRaw, long nowEpochSecond) {
        Long defaultTtl = asIntegral(defaultTtlRaw);
        if (defaultTtl == null) return false;          // TTL disabled on the container

        Long itemTtl   = asIntegral(doc.get("ttl"));
        long effective = itemTtl != null ? itemTtl : defaultTtl;
        if (effective <= 0) return false;              // -1 — never expires

        Long ts = asIntegral(doc.get("_ts"));
        if (ts == null) return false;
        return nowEpochSecond >= ts + effective;
    }

    /** Returns the value as a whole number, or {@code null} when absent, non-numeric, or fractional. */
    private static Long asIntegral(Object value) {
        if (value instanceof Integer || value instanceof Long) return ((Number) value).longValue();
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.floor(d) && !Double.isInfinite(d) ? (long) d : null;
        }
        return null;
    }
}
