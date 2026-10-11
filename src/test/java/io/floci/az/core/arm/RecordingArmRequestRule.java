package io.floci.az.core.arm;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * A test-only {@link ArmRequestRule} that records every call the gate sees. It is an {@link Alternative}
 * without a priority, so it joins the gate only under a test profile that selects it; every other test runs
 * with the production gate, which has no rules.
 */
@Alternative
@ApplicationScoped
public class RecordingArmRequestRule implements ArmRequestRule {

    /** One gate passage: the call built on entry, and the status seen on exit (null until exit, or when the handler threw). */
    public record Passage(ArmCall call, Integer exitStatus) {}

    private final List<ArmCall> entered = new ArrayList<>();
    private final List<Passage> exited = new ArrayList<>();

    @Override
    public int order() {
        return 0;
    }

    @Override
    public synchronized void enter(ArmCall call) {
        entered.add(call);
    }

    @Override
    public synchronized void exit(ArmCall call, Response response) {
        exited.add(new Passage(call, response == null ? null : response.getStatus()));
    }

    public synchronized List<ArmCall> entered() {
        return List.copyOf(entered);
    }

    public synchronized List<Passage> exited() {
        return List.copyOf(exited);
    }

    public synchronized void clear() {
        entered.clear();
        exited.clear();
    }
}
