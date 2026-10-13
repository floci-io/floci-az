package io.floci.az.services.entra;

import io.floci.az.services.entra.EntraModels.AuthorizationCode;
import io.floci.az.services.entra.EntraModels.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * {@code grant_type=authorization_code}: redeems a single-use code from {@link AuthorizeEndpoint}.
 * Verifies that the tenant, {@code redirect_uri} and {@code client_id} match the {@code /authorize}
 * request and checks the PKCE {@code code_verifier}, then issues an access token plus an ID token
 * (echoing the original {@code nonce}) for the code's user.
 */
@ApplicationScoped
public class AuthorizationCodeGrant implements GrantHandler {

    private static final Logger LOG = Logger.getLogger(AuthorizationCodeGrant.class);

    private final TokenIssuer tokenIssuer;
    private final EntraStore store;
    private final EntraResponses responses;

    @Inject
    public AuthorizationCodeGrant(TokenIssuer tokenIssuer, EntraStore store, EntraResponses responses) {
        this.tokenIssuer = tokenIssuer;
        this.store = store;
        this.responses = responses;
    }

    @Override
    public String grantType() {
        return "authorization_code";
    }

    @Override
    public Response handle(TokenRequest request) {
        String code = request.form().get("code");
        if (code == null || code.isBlank()) {
            return responses.error(AadstsError.MISSING_CODE);
        }
        Optional<AuthorizationCode> stored = store.consumeAuthorizationCode(code);
        if (stored.isEmpty()) {
            return responses.error(AadstsError.CODE_INVALID);
        }
        AuthorizationCode authCode = stored.get();
        if (authCode.expiresAt().isBefore(Instant.now())) {
            return responses.error(AadstsError.CODE_EXPIRED);
        }
        if (!request.tenantId().equals(authCode.tenantId())) {
            return responses.error(AadstsError.CODE_TENANT_MISMATCH);
        }
        String redirectUri = request.form().get("redirect_uri");
        if (redirectUri == null || !redirectUri.equals(authCode.redirectUri())) {
            return responses.error(AadstsError.REDIRECT_URI_MISMATCH);
        }
        String clientId = request.clientId();
        if (clientId == null || !clientId.equals(authCode.clientId())) {
            return responses.error(AadstsError.CLIENT_ID_MISMATCH);
        }
        if (!verifyPkce(authCode, request.form().get("code_verifier"))) {
            return responses.error(AadstsError.PKCE_MISMATCH);
        }
        User user = store.getUser(authCode.userObjectId()).orElse(null);
        if (user == null) {
            return responses.error(AadstsError.CODE_UNKNOWN_USER);
        }

        String appId = request.appId();
        String scp = Scopes.normalize(authCode.scope());
        String accessAudience = request.v2() ? appId : Scopes.firstScope(authCode.scope(), request.baseUrl());
        String accessToken = tokenIssuer.issue(new TokenIssuer.TokenSpec(
                request.tenantId(), request.issuer(), accessAudience, user.objectId(), user.objectId(), appId,
                scp, request.tokenVersion(), null, request.lifetimeSeconds()));
        // The ID token audience is always the client id, whatever the API version: that is OIDC, not Azure.
        String idToken = tokenIssuer.issueIdToken(new TokenIssuer.TokenSpec(
                request.tenantId(), request.issuer(), appId, user.objectId(), user.objectId(), appId, scp,
                request.tokenVersion(), null, request.lifetimeSeconds()),
                authCode.nonce(), user.displayName(), user.upn(), user.email());
        return responses.token(accessToken, idToken, request.lifetimeSeconds(), scp);
    }

    /**
     * PKCE verification is permissive: an {@code /authorize} request that omitted
     * {@code code_challenge} skips verification here too, matching the permissive client
     * validation elsewhere in the token service.
     */
    private static boolean verifyPkce(AuthorizationCode authCode, String codeVerifier) {
        String challenge = authCode.codeChallenge();
        if (challenge == null || challenge.isBlank()) {
            return true;
        }
        if (codeVerifier == null || codeVerifier.isBlank()) {
            return false;
        }
        if (!"S256".equalsIgnoreCase(authCode.codeChallengeMethod())) {
            return codeVerifier.equals(challenge);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return computed.equals(challenge);
        } catch (NoSuchAlgorithmException e) {
            LOG.errorv(e, "Entra: SHA-256 is unavailable, rejecting the PKCE verifier");
            return false;
        }
    }
}
