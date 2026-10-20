package io.floci.az.services.entra;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;

/**
 * {@code grant_type=password} (resource owner password credentials): a delegated token for
 * {@code username}. The password is not checked.
 */
@ApplicationScoped
public class PasswordGrant implements GrantHandler {

    private final TokenIssuer tokenIssuer;
    private final EntraResponses responses;

    @Inject
    public PasswordGrant(TokenIssuer tokenIssuer, EntraResponses responses) {
        this.tokenIssuer = tokenIssuer;
        this.responses = responses;
    }

    @Override
    public String grantType() {
        return "password";
    }

    @Override
    public Response handle(TokenRequest request) {
        String username = request.form().getOrDefault("username", EntraStore.DEV_USER_UPN);
        String userOid = TokenIssuer.deterministicGuid(username);
        String audience = request.v2() ? request.appId() : Scopes.firstScope(request.scope(), request.baseUrl());
        String scp = Scopes.normalize(request.scope());
        String token = tokenIssuer.issue(new TokenIssuer.TokenSpec(
                request.tenantId(), request.issuer(), audience, userOid, userOid, request.appId(), scp,
                request.tokenVersion(), null, request.lifetimeSeconds()));
        // MSAL asks for openid and client_info=1 on every password grant, and MSAL4J returns no account
        // (which azure-identity dereferences) unless the response carries both an ID token and client_info.
        String idToken = requestsOpenId(request.scope())
                ? tokenIssuer.issueIdToken(new TokenIssuer.TokenSpec(
                        request.tenantId(), request.issuer(), request.appId(), userOid, userOid, request.appId(),
                        scp, request.tokenVersion(), null, request.lifetimeSeconds()),
                        null, null, username, null)
                : null;
        String clientInfo = "1".equals(request.form().get("client_info"))
                ? responses.clientInfo(userOid, request.tenantId())
                : null;
        return responses.token(token, idToken, request.lifetimeSeconds(), scp, clientInfo);
    }

    private static boolean requestsOpenId(String scope) {
        return scope != null && Arrays.asList(scope.trim().split("\\s+")).contains("openid");
    }
}
