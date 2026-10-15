package io.floci.az.core.arm;

import jakarta.ws.rs.core.Response;

/**
 * A check the {@link ArmRequestGate} runs around every ARM request, before and after its handler. Rules run in
 * ascending {@link #order()}; two rules with the same order are a wiring bug and fail startup.
 */
public interface ArmRequestRule {

    /** Position in the gate's pipeline; lower runs first on entry. */
    int order();

    /**
     * Whether this rule reads the body of a PUT or PATCH. When any rule does, the gate buffers the body once
     * on entry, hands it to every rule as {@link ArmCall#body()}, and the handler reads it again afterwards,
     * amendments included.
     */
    default boolean readsBody() {
        return false;
    }

    /**
     * Runs before the handler. A {@link ArmVerdict.Respond} answers the request in the handler's place: the
     * rules after this one are not entered and the handler does not run.
     */
    default ArmVerdict enter(ArmCall call) {
        return ArmVerdict.proceed();
    }

    /**
     * Runs after the handler, in the same order as {@link #enter}, for every rule that was entered: when a rule
     * answered the request itself, the rules before it and that rule see its response.
     *
     * @param response the response the client gets, or null when the handler or an entry check threw
     */
    default void exit(ArmCall call, Response response) {
    }
}
