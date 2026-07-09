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
}
