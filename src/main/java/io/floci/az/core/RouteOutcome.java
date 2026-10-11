package io.floci.az.core;

import jakarta.ws.rs.core.Response;

/**
 * What a routing stage decided. Replaces the overloaded {@code null} return of the original filter, where
 * "no route matched, keep going" and "matched, but deliberately let JAX-RS answer" were the same value.
 */
sealed interface RouteOutcome permits RouteOutcome.Handled, RouteOutcome.Fallthrough {

    /** A handler produced a response; routing stops here. */
    record Handled(Response response) implements RouteOutcome {}

    enum Fallthrough implements RouteOutcome {
        /** Decline: try the next routing stage. */
        TO_NEXT_STAGE,
        /** Stop routing and let JAX-RS answer (normally a 404). */
        TO_JAX_RS
    }
}
