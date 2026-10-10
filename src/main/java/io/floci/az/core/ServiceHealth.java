package io.floci.az.core;

import java.util.Map;

/**
 * A service component that can be unusable while floci-az itself keeps serving, such as a
 * sidecar container that failed to start. Implementations are discovered via CDI
 * ({@code Instance<ServiceHealth>}); {@code /health} and {@code /ready} report {@code DOWN}
 * while any of them reports a problem.
 */
public interface ServiceHealth {

    /** Problems that leave this service unusable, keyed by component; empty when healthy. */
    Map<String, String> problems();
}
