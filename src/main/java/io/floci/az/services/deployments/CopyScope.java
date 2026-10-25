package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The scope of one iteration of a copy loop: everything is its enclosing scope's, except {@code copyIndex()}.
 * {@code copyIndex('name')} answers this loop's index when the name is this loop's and asks the enclosing scope
 * otherwise; an unnamed {@code copyIndex()} answers it only for a resource or output loop, the loops an unnamed call
 * refers to ({@code template-functions-numeric.md}, copyIndex), so a property loop inside a resource loop still
 * answers the resource's index there.
 */
final class CopyScope implements TemplateScope {

    private final TemplateScope parent;
    private final String loopName;
    private final long index;
    private final boolean answersUnnamed;

    /**
     * @param parent         the enclosing scope
     * @param loopName       this loop's name, or null for an output loop, which has none
     * @param index          this iteration, from zero
     * @param answersUnnamed true for a resource or output loop
     */
    CopyScope(TemplateScope parent, String loopName, long index, boolean answersUnnamed) {
        this.parent = parent;
        this.loopName = loopName;
        this.index = index;
        this.answersUnnamed = answersUnnamed;
    }

    @Override
    public long copyIndex(String name) {
        if (name == null) {
            return answersUnnamed ? index : parent.copyIndex(null);
        }
        return loopName != null && loopName.equalsIgnoreCase(name) ? index : parent.copyIndex(name);
    }

    @Override
    public JsonNode parameter(String name) {
        return parent.parameter(name);
    }

    @Override
    public JsonNode variable(String name) {
        return parent.variable(name);
    }

    @Override
    public JsonNode resourceGroup() {
        return parent.resourceGroup();
    }

    @Override
    public JsonNode subscription() {
        return parent.subscription();
    }

    @Override
    public JsonNode tenant() {
        return parent.tenant();
    }

    @Override
    public JsonNode deployment() {
        return parent.deployment();
    }

    @Override
    public JsonNode environment() {
        return parent.environment();
    }

    @Override
    public JsonNode reference(String resourceNameOrId, String apiVersion, boolean full) {
        return parent.reference(resourceNameOrId, apiVersion, full);
    }

    @Override
    public JsonNode list(String resourceId, String action, String apiVersion, JsonNode body) {
        return parent.list(resourceId, action, apiVersion, body);
    }
}
