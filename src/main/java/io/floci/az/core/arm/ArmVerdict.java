package io.floci.az.core.arm;

import jakarta.ws.rs.core.Response;

/** What an {@link ArmRequestRule} decides on entry: let the request through, or answer it in the handler's place. */
public sealed interface ArmVerdict {

    /** The request goes on to the next rule, and after the last rule to its handler. */
    record Proceed() implements ArmVerdict {
    }

    /** The request is answered with {@code response}; no later rule's entry check and no handler runs. */
    record Respond(Response response) implements ArmVerdict {
    }

    ArmVerdict PROCEED = new Proceed();

    static ArmVerdict proceed() {
        return PROCEED;
    }

    static ArmVerdict respond(Response response) {
        return new Respond(response);
    }
}
