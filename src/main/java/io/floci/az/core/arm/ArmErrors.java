package io.floci.az.core.arm;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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
     * {@code 409 ResourceGroupBeingDeleted} for a write under a resource group whose delete is running. Azure
     * documents the code; the status and the message are an emulator choice.
     */
    public static Response resourceGroupBeingDeleted(String resourceGroup) {
        return error(409, "ResourceGroupBeingDeleted", "The resource group '" + resourceGroup
            + "' is in deprovisioning state and cannot perform this operation.");
    }
}
