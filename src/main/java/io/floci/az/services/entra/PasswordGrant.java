package io.floci.az.services.entra;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.entra.DirectoryReader.DirectoryUser;
import io.floci.az.services.entra.EntraModels.RefreshToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * {@code grant_type=password} (resource owner password credentials): a delegated token for
 * {@code username}. Directory password validation is opt-in.
 */
@ApplicationScoped
public class PasswordGrant implements GrantHandler {

    private final TokenIssuer tokenIssuer;
    private final EntraResponses responses;
    private final DirectoryReader directory;
    private final EmulatorConfig config;
    private final EntraStore store;

    @Inject
    public PasswordGrant(TokenIssuer tokenIssuer, EntraResponses responses, DirectoryReader directory,
                         EmulatorConfig config, EntraStore store) {
        this.tokenIssuer = tokenIssuer;
        this.responses = responses;
        this.directory = directory;
        this.config = config;
        this.store = store;
    }

    @Override
    public String grantType() {
        return "password";
    }

    @Override
    public Response handle(TokenRequest request) {
        boolean enforced = config.services().entra().validateUserCredentials();
        if (enforced) {
            for (String required : new String[]{"username", "password", "client_id"}) {
                String value = "client_id".equals(required) ? request.clientId() : request.form().get(required);
                if (value == null || value.isBlank()) {
                    return responses.error(AadstsError.MISSING_REQUIRED_PARAMETER, required);
                }
            }
        }
        String username = request.form().getOrDefault("username", EntraStore.DEV_USER_UPN);
        DirectoryUser user = directory.userByPrincipalName(request.directoryTenantId(), username).orElse(null);
        if (enforced && (!request.tenantId().equals(request.directoryTenantId())
                || user == null || !directory.userPasswordMatches(request.directoryTenantId(), user.id(),
                request.form().get("password")))) {
            return responses.error(AadstsError.INVALID_USER_CREDENTIALS);
        }
        if (enforced && !user.accountEnabled()) {
            return responses.error(AadstsError.USER_DISABLED);
        }
        if (enforced && user.passwordChangeRequired()) {
            return responses.error(AadstsError.PASSWORD_CHANGE_REQUIRED);
        }
        String userOid = user == null ? TokenIssuer.deterministicGuid(username) : user.id();
        String principalName = user == null ? username : user.userPrincipalName();
        String audience = request.v2() ? request.appId() : Scopes.firstScope(request.scope(), request.baseUrl());
        String scp = Scopes.normalize(request.scope());
        String token = tokenIssuer.issue(new TokenIssuer.TokenSpec(
                request.tenantId(), request.issuer(), audience, userOid, userOid, request.appId(),
                Scopes.delegatedScopes(request.scope()),
                request.tokenVersion(), null, request.lifetimeSeconds()),
                TokenIssuer.userClaims(request.tokenVersion(), principalName));
        // MSAL asks for openid and client_info=1 on every password grant, and MSAL4J returns no account
        // (which azure-identity dereferences) unless the response carries both an ID token and client_info.
        String idToken = Scopes.values(request.scope()).contains("openid")
                ? tokenIssuer.issueIdToken(new TokenIssuer.TokenSpec(
                        request.tenantId(), request.issuer(), request.appId(), userOid, userOid, request.appId(),
                        scp, request.tokenVersion(), null, request.lifetimeSeconds()),
                        null, user == null ? null : user.displayName(), principalName, user == null ? null : user.mail())
                : null;
        String clientInfo = "1".equals(request.form().get("client_info"))
                ? responses.clientInfo(userOid, request.tenantId())
                : null;
        String refreshToken = null;
        if (request.v2() && user != null && request.clientId() != null
                && Scopes.values(request.scope()).contains("offline_access")) {
            refreshToken = store.issueRefreshToken(new RefreshToken(request.tenantId(), request.directoryTenantId(),
                    request.clientId(), user.id(), request.scope(), request.clientCredentialPresent(), false,
                    RefreshTokenGrant.expiry(false)));
        }
        return responses.token(token, idToken, request.lifetimeSeconds(), scp, clientInfo, refreshToken);
    }
}
