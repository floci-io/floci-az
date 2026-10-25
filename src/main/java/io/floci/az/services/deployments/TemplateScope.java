package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Everything an expression can read beyond its own arguments: the template's parameters and variables, the
 * deployment's scope objects, and the resources it can reference. The deployment engine implements it; the
 * {@link ExpressionEvaluator} only calls it.
 */
public interface TemplateScope {

    /** The value of parameter {@code name}; throws {@link TemplateException} for an unknown one. */
    JsonNode parameter(String name);

    /** The value of variable {@code name}; throws {@link TemplateException} for an unknown one. */
    JsonNode variable(String name);

    /** {@code resourceGroup()}: id, name, type, location, tags, properties. */
    JsonNode resourceGroup();

    /** {@code subscription()}: id, subscriptionId, tenantId, displayName. */
    JsonNode subscription();

    /** {@code tenant()}: tenantId and the tenant's properties. */
    JsonNode tenant();

    /** {@code deployment()}: name, properties and the rest of the deployment object. */
    JsonNode deployment();

    /** {@code environment()}: name, resourceManager, suffixes and the rest of the cloud's endpoints. */
    JsonNode environment();

    /**
     * {@code reference()}: a resource's runtime state, by the name of a resource in the template or by a full id.
     *
     * @param apiVersion the requested api-version, or null for the resource's own
     * @param full       true for the {@code 'Full'} form (the whole resource), false for its properties
     */
    JsonNode reference(String resourceNameOrId, String apiVersion, boolean full);

    /**
     * A {@code list*} function ({@code listKeys}, {@code listSecrets}, ...): the action {@code action} posted to the
     * resource.
     *
     * @param body the function's optional values, or null
     */
    JsonNode list(String resourceId, String action, String apiVersion, JsonNode body);

    /**
     * Charges {@code iterations} copy-loop iterations to the evaluation of one template, before they are built;
     * throws {@link TemplateException} once the template expands past what it may (see {@link CopyLoops}). A scope
     * that does not evaluate a whole template counts nothing.
     */
    default void expand(int iterations) {
    }

    /**
     * {@code copyIndex()}: the zero-based iteration of the copy loop named {@code loopName}, or of the resource's
     * own loop when it is null. Outside a loop of that name it is an error, as in Azure.
     */
    default long copyIndex(String loopName) {
        throw TemplateException.invalid(loopName == null
            ? "The template function 'copyIndex' is not expected here: it is only valid inside a copy loop."
            : "The template function 'copyIndex' refers to the copy loop '" + loopName
                + "', which does not enclose this expression.");
    }
}
