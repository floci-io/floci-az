package io.floci.az.services.entra;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** {@code grant_type=client_credentials}: an app-only token for the client's service principal. */
@ApplicationScoped
public class ClientCredentialsGrant implements GrantHandler {

    private final TokenIssuer tokenIssuer;
    private final EntraResponses responses;

    @Inject
    public ClientCredentialsGrant(TokenIssuer tokenIssuer, EntraResponses responses) {
        this.tokenIssuer = tokenIssuer;
        this.responses = responses;
    }

    @Override
    public String grantType() {
        return "client_credentials";
    }

    @Override
    public Response handle(TokenRequest request) {
        String audience = Scopes.clientCredentialsAudience(request.scope(), request.baseUrl());
        String token = tokenIssuer.issue(new TokenIssuer.TokenSpec(
                request.tenantId(), request.issuer(), audience, request.clientObjectId(),
                request.clientObjectId(), request.appId(), null, request.tokenVersion(), "app",
                request.lifetimeSeconds()));
        return responses.token(token, null, request.lifetimeSeconds(), null);
    }
}
