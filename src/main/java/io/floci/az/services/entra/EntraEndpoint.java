package io.floci.az.services.entra;

import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * One route the token service serves under {@code /{tenant}/...}. Implementations are CDI beans
 * collected by {@link EntraServiceHandler}; an endpoint that is not registered is neither routed
 * nor advertised, because {@link DiscoveryProvider} builds the discovery document only from what
 * the registered endpoints declare.
 */
public interface EntraEndpoint {

    /** Whether this endpoint serves {@code path} (no leading or trailing slashes, tenant segment first). */
    boolean matches(String path);

    Response handle(EntraRequest request);

    /**
     * Adds this endpoint's discovery metadata ({@code token_endpoint}, {@code grant_types_supported},
     * ...) to {@code document}. The default advertises nothing.
     */
    default void advertise(EntraRequest request, Map<String, Object> document) {
    }
}
