package io.floci.az.config;

/**
 * How closely floci-az follows Azure where Azure differs from what floci-az has always answered.
 * {@code floci-az.parity}; the levels nest, each one including everything the level before it turns on.
 */
public enum ParityLevel {
    /** Default: every response a client could rely on stays as floci-az has always answered it. */
    COMPAT,
    /** Every parity override on: Azure's listings, ids and side effects, where they differ from compat. */
    AZURE,
    /** {@link #AZURE} plus every {@code strict-*} override: Azure's refusals. */
    STRICT;

    /** Whether this level turns on everything {@code level} does. */
    public boolean includes(ParityLevel level) {
        return compareTo(level) >= 0;
    }
}
