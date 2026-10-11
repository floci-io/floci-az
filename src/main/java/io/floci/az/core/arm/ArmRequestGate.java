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
 * handler with the call attached to the request, and runs the rules again on exit. Entry happens once per
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
     * Runs {@code handler} inside the gate: {@link #enter} before it, {@link #exit} after it, even when it
     * throws. A request already inside the gate is handed to {@code handler} unchanged.
     */
    public Response serve(AzureRequest request, Function<AzureRequest, Response> handler) {
        if (request.armCall() != null) {
            return handler.apply(request);
        }
        ArmCall call = enter(request);
        Response response = null;
        try {
            response = handler.apply(request.withArm(call));
            return response;
        } finally {
            exit(call, response);
        }
    }

    /** Builds the {@link ArmCall} for {@code request} and runs every rule's entry check. */
    public ArmCall enter(AzureRequest request) {
        ArmCall call = new ArmCall(request.scope(), ArmResourceId.parse(request.resourcePath()).orElse(null),
            null, request.method() == null ? null : request.method().toUpperCase(Locale.ROOT), null);
        for (ArmRequestRule rule : rules) {
            rule.enter(call);
        }
        return call;
    }

    /** Runs every rule's exit hook; {@code response} is null when the handler threw. */
    public void exit(ArmCall call, Response response) {
        for (ArmRequestRule rule : rules) {
            rule.exit(call, response);
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
