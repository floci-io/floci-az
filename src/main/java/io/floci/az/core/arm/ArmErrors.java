package io.floci.az.core.arm;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ARM CloudError responses — {@code {"error":{"code":"...","message":"..."}}}.
 * The management-plane counterpart of {@code core/AzureErrorResponse} (which covers
 * the storage data-plane XML/JSON shape).
 */
public final class ArmErrors {

    private ArmErrors() {
    }

    public static Response error(int status, String code, String message) {
        return Response.status(status)
                .entity(Map.of("error", Map.of("code", code, "message", message)))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /**
     * {@code 401} for a refused Bearer token: the ARM error envelope and a {@code WWW-Authenticate} challenge in the
     * form the SDK challenge parsers carry as their ARM fixture, pointing at the emulator's own login endpoint.
     * {@code description} goes into the header, so it must be a fixed text with no client-supplied value in it.
     */
    public static Response unauthorized(String code, String message, String authorizationUri, String description) {
        return Response.status(401)
                .entity(Map.of("error", Map.of("code", code, "message", message)))
                .type(MediaType.APPLICATION_JSON)
                .header("WWW-Authenticate", "Bearer authorization_uri=\"" + authorizationUri
                    + "/\", error=\"invalid_token\", error_description=\"" + description + "\"")
                .build();
    }

    public static Response notFound(String message) {
        return error(404, "ResourceNotFound", message);
    }

    /** {@code 404 SubscriptionNotFound} for a subscription the directory does not hold, with Azure's message. */
    public static Response subscriptionNotFound(String subscriptionId) {
        return error(404, "SubscriptionNotFound", "The subscription '" + subscriptionId + "' could not be found.");
    }

    /** {@code 404 ResourceGroupNotFound} for a resource group that does not exist, with Azure's message. */
    public static Response resourceGroupNotFound(String resourceGroup) {
        return error(404, "ResourceGroupNotFound", "Resource group '" + resourceGroup + "' could not be found.");
    }

    /**
     * {@code 409 MissingSubscriptionRegistration} for a request to a namespace the subscription is not registered
     * with, as Azure records it: the namespace is the last quoted value of the message, which the Python and Java
     * auto-register policies parse, and the {@code target} of the one detail, which Java reads first.
     */
    public static Response missingSubscriptionRegistration(String namespace) {
        String message = "The subscription is not registered to use namespace '" + namespace
            + "'. See https://aka.ms/rps-not-found for how to register subscriptions.";
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", "MissingSubscriptionRegistration");
        detail.put("target", namespace);
        detail.put("message", message);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "MissingSubscriptionRegistration");
        error.put("message", message);
        error.put("details", List.of(detail));
        return Response.status(409).entity(Map.of("error", error)).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * {@code 400 NoRegisteredProviderFound} for a location (or api-version) a resource type does not offer, worded as
     * Azure records it: the requested location and api-version, the type below its namespace, then the supported
     * api-versions and locations, each list joined with {@code ", "}.
     */
    public static Response noRegisteredProviderFound(String location, String apiVersion, String typePath,
                                                     List<String> apiVersions, List<String> locations) {
        return error(400, "NoRegisteredProviderFound", "No registered resource provider found for location '"
            + location + "' and API version '" + apiVersion + "' for type '" + typePath
            + "'. The supported api-versions are '" + String.join(", ", apiVersions)
            + "'. The supported locations are '" + String.join(", ", locations) + "'.");
    }

    /**
     * {@code 404 InvalidResourceNamespace} for a provider namespace floci-az does not serve. Azure documents the
     * code for an unknown namespace; no recording shows the Providers API answering one, so the status and the
     * message are an emulator choice.
     */
    public static Response invalidResourceNamespace(String namespace) {
        return error(404, "InvalidResourceNamespace", "The resource namespace '" + namespace + "' is invalid.");
    }

    /**
     * {@code 409 ResourceGroupBeingDeleted} for a write under a resource group whose delete is running. Azure
     * documents the code; the status and the message are an emulator choice.
     */
    public static Response resourceGroupBeingDeleted(String resourceGroup) {
        return error(409, "ResourceGroupBeingDeleted", "The resource group '" + resourceGroup
            + "' is in deprovisioning state and cannot perform this operation.");
    }
}
