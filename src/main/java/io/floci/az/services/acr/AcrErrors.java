package io.floci.az.services.acr;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Errors in the Docker Registry HTTP API V2 envelope,
 * {@code {"errors":[{"code":"...","message":"..."}]}}. The registry data plane and the ACR token
 * endpoints in front of it both answer in this shape; the ARM management plane keeps using
 * {@link io.floci.az.core.arm.ArmErrors}.
 */
final class AcrErrors {

    /** The requested operation is not implemented here (OCI distribution-spec {@code UNSUPPORTED}). */
    static final String UNSUPPORTED = "UNSUPPORTED";
    /** The registry data plane is not currently reachable (OCI distribution-spec {@code UNAVAILABLE}). */
    static final String UNAVAILABLE = "UNAVAILABLE";
    /** The named repository or registry is unknown (OCI distribution-spec {@code NAME_UNKNOWN}). */
    static final String NAME_UNKNOWN = "NAME_UNKNOWN";
    /** Authentication is required or the credential presented is unusable ({@code UNAUTHORIZED}). */
    static final String UNAUTHORIZED = "UNAUTHORIZED";

    private AcrErrors() {
    }

    static Response error(int status, String code, String message) {
        return Response.status(status)
                .entity(Map.of("errors", List.of(Map.of("code", code, "message", message))))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /** 400 for a malformed or unsupported token request. */
    static Response badRequest(String message) {
        return error(400, UNSUPPORTED, message);
    }
}
