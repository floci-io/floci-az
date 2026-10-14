package io.floci.az.core.arm;

import io.floci.az.core.AzureRequest;
import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The one place every ARM management-plane request passes before its handler runs and after it returns,
 * whichever lane serves it: a handler the routing filter dispatches directly, or a provider that
 * {@code ArmHandler} delegates to.
 *
 * <p>The gate builds an {@link ArmCall} on entry, runs the ordered {@link ArmRequestRule}s, invokes the
 * handler with the call attached to the request, and runs the rules again on exit. A rule may answer the
 * request itself on entry, which is how a refused request never reaches its handler. Entry happens once per
 * request: a request that already carries an {@link ArmCall} is inside the gate, so a nested
 * {@link #serve} passes it straight through.
 */
@ApplicationScoped
public class ArmRequestGate {

    private static final Logger LOG = Logger.getLogger(ArmRequestGate.class);

    private final List<ArmRequestRule> rules;

    @Inject
    public ArmRequestGate(@All List<ArmRequestRule> rules) {
        this.rules = ordered(rules);
        LOG.debugv("ARM request gate: {0} rules", this.rules.size());
    }

    /** The rules in the order they run. */
    public List<ArmRequestRule> rules() {
        return rules;
    }

    /**
     * Runs {@code handler} inside the gate: each rule's entry check before it and each entered rule's exit hook
     * after it, even when it throws. A rule that answers the request on entry ({@link ArmVerdict.Respond}) stands
     * in for the handler: no later rule is entered, the handler does not run, and the entered rules' exit hooks
     * see that response. A request already inside the gate is handed to {@code handler} unchanged.
     */
    public Response serve(AzureRequest request, Function<AzureRequest, Response> handler) {
        if (request.armCall() != null) {
            return handler.apply(request);
        }
        ArmCall call = call(request);
        if (rules.isEmpty()) {
            return handler.apply(request.withArm(call));
        }
        int entered = 0;
        Response response = null;
        try {
            for (ArmRequestRule rule : rules) {
                entered++;
                if (rule.enter(call) instanceof ArmVerdict.Respond respond) {
                    response = respond.response();
                    return response;
                }
            }
            response = handler.apply(request.withArm(call));
            return response;
        } finally {
            exit(call, response, entered);
        }
    }

    /** Builds the {@link ArmCall} the rules and the handler see for {@code request}. */
    public ArmCall call(AzureRequest request) {
        return new ArmCall(request.scope(), ArmResourceId.parse(request.resourcePath()).orElse(null),
            null, request.method() == null ? null : request.method().toUpperCase(Locale.ROOT), null, request);
    }

    /** Runs the exit hook of the first {@code entered} rules; {@code response} is null when something threw. */
    private void exit(ArmCall call, Response response, int entered) {
        for (int i = 0; i < entered; i++) {
            rules.get(i).exit(call, response);
        }
    }

    /** Sorts rules by {@link ArmRequestRule#order()}, failing on two rules that claim the same position. */
    static List<ArmRequestRule> ordered(List<ArmRequestRule> rules) {
        Map<Integer, ArmRequestRule> byOrder = new HashMap<>();
        for (ArmRequestRule rule : rules) {
            ArmRequestRule previous = byOrder.putIfAbsent(rule.order(), rule);
            if (previous != null) {
                throw new IllegalStateException("Two ARM request rules claim order " + rule.order() + ": "
                    + previous.getClass().getSimpleName() + " and " + rule.getClass().getSimpleName());
            }
        }
        List<ArmRequestRule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(ArmRequestRule::order));
        return List.copyOf(sorted);
    }
}
