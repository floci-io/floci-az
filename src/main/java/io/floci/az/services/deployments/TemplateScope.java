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
}
