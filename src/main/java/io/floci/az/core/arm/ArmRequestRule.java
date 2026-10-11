package io.floci.az.core.arm;

import jakarta.ws.rs.core.Response;

/**
 * A check the {@link ArmRequestGate} runs around every ARM request, before and after its handler. Rules run in
 * ascending {@link #order()}; two rules with the same order are a wiring bug and fail startup.
 */
public interface ArmRequestRule {

    /** Position in the gate's pipeline; lower runs first on entry. */
    int order();

    /** Runs before the handler. */
    default void enter(ArmCall call) {
    }

    /**
     * Runs after the handler, in the same order as {@link #enter}.
     *
     * @param response the handler's response, or null when the handler threw
     */
    default void exit(ArmCall call, Response response) {
    }
}
