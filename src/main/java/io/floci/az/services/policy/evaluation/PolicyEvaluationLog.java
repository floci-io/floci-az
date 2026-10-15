package io.floci.az.services.policy.evaluation;

import io.floci.az.core.Resettable;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/**
 * The policy effects that fired, newest last, kept in memory for inspection through
 * {@code GET /_admin/policy/evaluations} and, later, the Activity Log. Bounded so a long-running emulator
 * does not grow without limit.
 */
@ApplicationScoped
public class PolicyEvaluationLog implements Resettable {

    static final int CAPACITY = 10_000;

    private final Deque<PolicyEvaluationEvent> events = new ArrayDeque<>();

    public synchronized void record(PolicyEvaluationEvent event) {
        if (events.size() >= CAPACITY) {
            events.removeFirst();
        }
        events.addLast(event);
    }

    public synchronized List<PolicyEvaluationEvent> events() {
        return new ArrayList<>(events);
    }

    public synchronized List<PolicyEvaluationEvent> events(Predicate<PolicyEvaluationEvent> filter) {
        List<PolicyEvaluationEvent> out = new ArrayList<>();
        for (PolicyEvaluationEvent event : events) {
            if (filter.test(event)) {
                out.add(event);
            }
        }
        return out;
    }

    @Override
    public synchronized void clear() {
        events.clear();
    }
}
