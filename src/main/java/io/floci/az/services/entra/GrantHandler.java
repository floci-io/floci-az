package io.floci.az.services.entra;

import jakarta.ws.rs.core.Response;

/**
 * One OAuth 2.0 grant the token endpoint accepts. Implementations are CDI beans collected by
 * {@link TokenEndpoint}, which dispatches on {@code grant_type} and advertises exactly the
 * registered grants in {@code grant_types_supported}.
 */
public interface GrantHandler {

    /** The {@code grant_type} form value this handler redeems. */
    String grantType();

    /**
     * Whether the grant is for confidential clients only, so the client must present a secret or
     * an assertion when {@code entra.validate-client-credentials} is on.
     */
    default boolean requiresClientCredential() {
        return false;
    }

    Response handle(TokenRequest request);
}
