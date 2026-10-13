package io.floci.az.services.entra;

import jakarta.ws.rs.core.Response;

/**
 * One route the token service serves under {@code /{tenant}/...}. Implementations are CDI beans
 * collected by {@link EntraServiceHandler}, which hands each request to the first that matches.
 */
public interface EntraEndpoint {

    /** Whether this endpoint serves {@code path} (no leading or trailing slashes, tenant segment first). */
    boolean matches(String path);

    Response handle(EntraRequest request);
}
