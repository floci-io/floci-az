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
