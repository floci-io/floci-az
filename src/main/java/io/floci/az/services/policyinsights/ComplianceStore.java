package io.floci.az.services.policyinsights;

import io.floci.az.core.Resettable;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.services.policy.evaluation.PolicyEngine;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The latest compliance state of every resource against every applicable assignment: written by the
 * policy evaluation of each admitted request and by compliance scans, replaced per resource, assignment
 * and definition, and dropped when a resource or an assignment goes away.
 */
@ApplicationScoped
public class ComplianceStore implements Resettable {

    private final Map<String, PolicyState> states = new LinkedHashMap<>();

    /** Records the verdicts of one evaluation of {@code content} (the resource body with its computed fields). */
    public synchronized void record(ArmResourceId id, Map<String, Object> content, List<PolicyEngine.Evaluation> evaluations) {
        String resourceId = id.id();
        Instant now = Instant.now();
        for (PolicyEngine.Evaluation evaluation : evaluations) {
            Map<String, Object> assignment = evaluation.assignment();
            Map<String, Object> assignmentProperties = PolicyEngine.properties(assignment);
            Map<String, Object> definitionProperties = PolicyEngine.properties(evaluation.definition());
            Map<String, Object> metadata = definitionProperties.get("metadata") instanceof Map<?, ?> m ? cast(m) : Map.of();
            PolicyState state = new PolicyState(now, resourceId, String.valueOf(content.get("type")),
                content.get("location") instanceof String location ? location.toLowerCase(Locale.ROOT) : null,
                id.resourceGroup() == null ? null : id.resourceGroup().toLowerCase(Locale.ROOT), id.subscriptionId(),
                content.get("tags") instanceof Map<?, ?> tags ? cast(tags) : Map.of(),
                String.valueOf(assignment.get("id")), String.valueOf(assignment.get("name")),
                String.valueOf(assignmentProperties.get("scope")),
                assignmentProperties.get("parameters") instanceof Map<?, ?> p ? cast(p) : Map.of(),
                String.valueOf(evaluation.definition().get("id")), String.valueOf(evaluation.definition().get("name")),
                evaluation.effect(), metadata.get("category") instanceof String category ? category : null,
                evaluation.setDefinition() == null ? null : String.valueOf(evaluation.setDefinition().get("id")),
                evaluation.setDefinition() == null ? null : String.valueOf(evaluation.setDefinition().get("name")),
                evaluation.referenceId(), evaluation.parameters(),
                evaluation.exempt() ? "Exempt" : evaluation.matched() ? "NonCompliant" : "Compliant");
            states.put(state.key(), state);
        }
    }

    /** Forgets every record of a resource, when it is deleted or re-evaluated from scratch. */
    public synchronized void forgetResource(String resourceId) {
        String prefix = resourceId.toLowerCase(Locale.ROOT) + "|";
        states.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Forgets every record of an assignment, when it is deleted. */
    public synchronized void forgetAssignment(String assignmentId) {
        String needle = "|" + assignmentId.toLowerCase(Locale.ROOT) + "|";
        states.keySet().removeIf(key -> key.contains(needle));
    }

    public synchronized List<PolicyState> query(Predicate<PolicyState> filter) {
        List<PolicyState> out = new ArrayList<>();
        for (PolicyState state : states.values()) {
            if (filter.test(state)) {
                out.add(state);
            }
        }
        return out;
    }

    public synchronized List<PolicyState> all() {
        return new ArrayList<>(states.values());
    }

    @Override
    public synchronized void clear() {
        states.clear();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
