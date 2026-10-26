package io.floci.az.services.entra;

import io.floci.az.services.entra.DirectoryReader.DirectoryUser;
import io.floci.az.services.entra.EntraModels.RefreshToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.time.Instant;

/** Redeems opaque, directory-backed refresh consent at the v2 token endpoint. */
@ApplicationScoped
public class RefreshTokenGrant implements GrantHandler {
    private final EntraStore store;
    private final DirectoryReader directory;
    private final TokenIssuer issuer;
    private final EntraResponses responses;
    private final ClientAuthenticator authenticator;

    @Inject
    public RefreshTokenGrant(EntraStore store, DirectoryReader directory, TokenIssuer issuer,
                             EntraResponses responses, ClientAuthenticator authenticator) {
        this.store = store;
        this.directory = directory;
        this.issuer = issuer;
        this.responses = responses;
        this.authenticator = authenticator;
    }

    static Instant expiry(boolean spa) {
        return Instant.now().plus(Duration.ofDays(spa ? 1 : 90));
    }

    @Override
    public String grantType() {
        return "refresh_token";
    }

    @Override
    public Response handle(TokenRequest request) {
        if (!request.v2()) {
            return responses.error(AadstsError.UNSUPPORTED_GRANT_TYPE, grantType());
        }
        String token = request.form().get("refresh_token");
        if (token == null || token.isBlank()) {
            return responses.error(AadstsError.MISSING_REQUIRED_PARAMETER, "refresh_token");
        }
        if (request.clientId() == null) {
            return responses.error(AadstsError.MISSING_REQUIRED_PARAMETER, "client_id");
        }
        RefreshToken grant = store.getRefreshToken(token).orElse(null);
        if (grant == null || !request.clientId().equals(grant.clientId())
                || !request.tenantId().equals(grant.tenantId())
                || !request.directoryTenantId().equals(grant.directoryTenantId())) {
            return responses.error(AadstsError.REFRESH_TOKEN_INVALID);
        }
        if (!grant.expiresAt().isAfter(Instant.now())) {
            return responses.error(grant.spa() ? AadstsError.SPA_REFRESH_TOKEN_EXPIRED : AadstsError.REFRESH_TOKEN_EXPIRED);
        }
        if (grant.confidentialClient() && authenticator.enforced() && !request.clientCredentialPresent()) {
            return responses.error(AadstsError.MISSING_CLIENT_CREDENTIAL);
        }
        String scope = request.scope() == null || request.scope().isBlank() ? grant.scope() : request.scope();
        if (!Scopes.values(grant.scope()).containsAll(Scopes.values(scope))) {
            return responses.error(AadstsError.INVALID_SCOPE);
        }
        DirectoryUser user = directory.user(grant.directoryTenantId(), grant.userObjectId()).orElse(null);
        if (user == null) {
            return responses.error(AadstsError.REFRESH_TOKEN_INVALID);
        }

        String access = issuer.issue(new TokenIssuer.TokenSpec(request.tenantId(), request.issuer(), request.appId(),
                user.id(), user.id(), request.appId(), Scopes.delegatedScopes(scope), request.tokenVersion(), null,
                request.lifetimeSeconds()), TokenIssuer.userClaims(request.tokenVersion(), user.userPrincipalName()));
        String idToken = Scopes.values(scope).contains("openid") ? issuer.issueIdToken(new TokenIssuer.TokenSpec(
                request.tenantId(), request.issuer(), request.appId(), user.id(), user.id(), request.appId(),
                Scopes.normalize(scope), request.tokenVersion(), null, request.lifetimeSeconds()), null,
                user.displayName(), user.userPrincipalName(), user.mail()) : null;
        RefreshToken replacement = new RefreshToken(grant.tenantId(), grant.directoryTenantId(), grant.clientId(),
                grant.userObjectId(), grant.scope(), grant.confidentialClient(), grant.spa(),
                grant.spa() ? grant.expiresAt() : expiry(false));
        String renewed = store.renewRefreshToken(token, grant, replacement).orElse(null);
        if (renewed == null) {
            return responses.error(AadstsError.REFRESH_TOKEN_INVALID);
        }
        String clientInfo = "1".equals(request.form().get("client_info"))
                ? responses.clientInfo(user.id(), grant.directoryTenantId()) : null;
        return responses.token(access, idToken, request.lifetimeSeconds(), Scopes.normalize(scope), clientInfo, renewed);
    }
}
