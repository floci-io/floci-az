package io.floci.az.services.policy;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory ARM resource state for the Microsoft.Authorization policy resources: definitions, set
 * definitions, assignments and exemptions. Like the other ARM control-plane stores (Managed Identity,
 * Network) this does not use a StorageBackend: ARM state is ephemeral by convention.
 *
 * <p>Every map is keyed by the lower-cased ARM resource id, so lookups are scope- and
 * name-insensitive the way ARM itself is. All access goes through methods: the handler holds a
 * normal-scoped client proxy, and direct field reads on a proxy would hit the proxy's own (empty)
 * maps instead of this contextual instance.</p>
 */
@ApplicationScoped
public class PolicyStore {

    private final Map<String, Map<String, Object>> definitions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> setDefinitions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> assignments = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> exemptions = new ConcurrentHashMap<>();

    static String key(String resourceId) {
        return resourceId.toLowerCase(Locale.ROOT);
    }

    // ── Definitions ─────────────────────────────────────────────────────────────

    public Map<String, Object> getDefinition(String id) {
        return definitions.get(key(id));
    }

    public void putDefinition(String id, Map<String, Object> resource) {
        definitions.put(key(id), resource);
    }

    public Map<String, Object> removeDefinition(String id) {
        return definitions.remove(key(id));
    }

    public List<Map<String, Object>> listDefinitions() {
        return new ArrayList<>(definitions.values());
    }

    // ── Set definitions ─────────────────────────────────────────────────────────

    public Map<String, Object> getSetDefinition(String id) {
        return setDefinitions.get(key(id));
    }

    public void putSetDefinition(String id, Map<String, Object> resource) {
        setDefinitions.put(key(id), resource);
    }

    public Map<String, Object> removeSetDefinition(String id) {
        return setDefinitions.remove(key(id));
    }

    public List<Map<String, Object>> listSetDefinitions() {
        return new ArrayList<>(setDefinitions.values());
    }

    /** Id of the first set definition whose {@code policyDefinitions} reference {@code definitionId}. */
    public Optional<String> findSetDefinitionReferencing(String definitionId) {
        String wanted = key(definitionId);
        for (Map<String, Object> set : setDefinitions.values()) {
            if (properties(set).get("policyDefinitions") instanceof List<?> references) {
                for (Object reference : references) {
                    if (reference instanceof Map<?, ?> ref
                            && ref.get("policyDefinitionId") instanceof String id
                            && wanted.equals(key(id))) {
                        return Optional.of(String.valueOf(set.get("id")));
                    }
                }
            }
        }
        return Optional.empty();
    }

    // ── Assignments ─────────────────────────────────────────────────────────────

    public Map<String, Object> getAssignment(String id) {
        return assignments.get(key(id));
    }

    public void putAssignment(String id, Map<String, Object> resource) {
        assignments.put(key(id), resource);
    }

    public Map<String, Object> removeAssignment(String id) {
        return assignments.remove(key(id));
    }

    public List<Map<String, Object>> listAssignments() {
        return new ArrayList<>(assignments.values());
    }

    /** Id of the first assignment whose {@code policyDefinitionId} is {@code definitionId} (definition or set). */
    public Optional<String> findAssignmentReferencing(String definitionId) {
        String wanted = key(definitionId);
        for (Map<String, Object> assignment : assignments.values()) {
            if (properties(assignment).get("policyDefinitionId") instanceof String id && wanted.equals(key(id))) {
                return Optional.of(String.valueOf(assignment.get("id")));
            }
        }
        return Optional.empty();
    }

    // ── Exemptions ──────────────────────────────────────────────────────────────

    public Map<String, Object> getExemption(String id) {
        return exemptions.get(key(id));
    }

    public void putExemption(String id, Map<String, Object> resource) {
        exemptions.put(key(id), resource);
    }

    public Map<String, Object> removeExemption(String id) {
        return exemptions.remove(key(id));
    }

    public List<Map<String, Object>> listExemptions() {
        return new ArrayList<>(exemptions.values());
    }

    /** Removes every exemption bound to {@code assignmentId}; Azure deletes them with the assignment. */
    public void removeExemptionsForAssignment(String assignmentId) {
        String wanted = key(assignmentId);
        exemptions.values().removeIf(exemption ->
                properties(exemption).get("policyAssignmentId") instanceof String id && wanted.equals(key(id)));
    }

    public void clear() {
        definitions.clear();
        setDefinitions.clear();
        assignments.clear();
        exemptions.clear();
    }

    private static Map<?, ?> properties(Map<String, Object> resource) {
        return resource.get("properties") instanceof Map<?, ?> props ? props : Map.of();
    }
}
