package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.RequestUrls;
import io.floci.az.services.entra.EntraModels.AppRegistration;
import io.floci.az.services.entra.EntraModels.AuthorizationCode;
import io.floci.az.services.entra.EntraModels.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Microsoft Entra ID (Azure AD) emulation — a local OpenID Connect provider.
 *
 * <p>Tenant-rooted paths arriving at the ARM base URL (port 4577):
 * <ul>
 *   <li>{@code {tenant}/oauth2/v2.0/token} and {@code {tenant}/oauth2/token} — token endpoint</li>
 *   <li>{@code {tenant}/discovery/v2.0/keys} — JWKS</li>
 *   <li>{@code {tenant}/v2.0/.well-known/openid-configuration} and
 *       {@code {tenant}/.well-known/openid-configuration} — discovery</li>
 * </ul>
 * where {@code tenant} may be a tenant id or {@code common}/{@code organizations}/{@code consumers}.
 *
 * <p>PR1 grants: {@code client_credentials} and {@code password} (ROPC). Client credentials are
 * accepted permissively (strict validation arrives in PR2).
 */
@ApplicationScoped
public class EntraServiceHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(EntraServiceHandler.class);

    private final EmulatorConfig config;
    private final SigningKeyProvider keys;
    private final TokenIssuer tokenIssuer;
    private final DiscoveryProvider discovery;
    private final EntraStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public EntraServiceHandler(EmulatorConfig config, SigningKeyProvider keys,
                               TokenIssuer tokenIssuer, DiscoveryProvider discovery,
                               EntraStore store) {
        this.config = config;
        this.keys = keys;
        this.tokenIssuer = tokenIssuer;
        this.discovery = discovery;
        this.store = store;
    }

    @Override public String getServiceType() { return "entra"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().entra().enabled();
    }

    @Override public boolean canHandle(AzureRequest request) {
        return "entra".equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest request) {
        String path = stripSlashes(request.resourcePath());
        String baseUrl = RequestUrls.resolveBaseUrl(request, config);
        LOG.infof("EntraService: %s %s", request.method(), path);

        if (path.endsWith(".well-known/openid-configuration")) {
            return json(discovery.document(baseUrl, tenantSegment(path)));
        }
        if (path.endsWith("discovery/v2.0/keys")) {
            return json(keys.jwks());
        }
        if (path.endsWith("oauth2/v2.0/token") || path.endsWith("oauth2/token")) {
            boolean v2 = path.endsWith("v2.0/token");
            return handleToken(request, tenantSegment(path), baseUrl, v2);
        }
        if (path.endsWith("oauth2/v2.0/authorize")) {
            return handleAuthorize(request);
        }
        return oauthError("invalid_request", "Unsupported Entra endpoint: " + path, 404);
    }

    // ── Authorize endpoint ──────────────────────────────────────────────────────

    /**
     * {@code GET /{tenant}/oauth2/v2.0/authorize} — auth-code+PKCE, local-dev shaped: there is no
     * real interactive consent screen, the request is auto-approved against a seeded dev user
     * (selectable via {@code login_hint}) and immediately redirected back with a code.
     */
    private Response handleAuthorize(AzureRequest request) {
        Map<String, String> params = request.queryParams();
        String redirectUri = params.get("redirect_uri");
        String responseType = params.getOrDefault("response_type", "code");
        String responseMode = params.getOrDefault("response_mode", "query");
        String loginHint = params.get("login_hint");

        if (redirectUri == null || redirectUri.isBlank()) {
            return oauthError("invalid_request", "redirect_uri is required", 400);
        }
        if (!"code".equals(responseType)) {
            return oauthError("unsupported_response_type",
                    "response_type '" + responseType + "' is not supported in this phase", 400);
        }

        User user = (loginHint == null ? Optional.<User>empty() : store.findUserByUpn(loginHint))
                .or(() -> store.getUser(EntraStore.DEV_USER_OBJECT_ID))
                .orElse(null);
        if (user == null) {
            return oauthError("invalid_request", "no dev user available to auto-approve the request", 400);
        }

        String code = UUID.randomUUID().toString();
        store.putAuthorizationCode(new AuthorizationCode(
                code, params.get("client_id"), redirectUri, params.get("code_challenge"),
                params.getOrDefault("code_challenge_method", "plain"), params.get("scope"),
                user.objectId(), params.get("nonce"), params.get("state"),
                Instant.now().plusSeconds(300)));

        return Response.status(Response.Status.FOUND)
                .location(URI.create(buildRedirect(redirectUri, responseMode, code, params.get("state"))))
                .build();
    }

    /** Appends {@code code}/{@code state} as a query string ({@code response_mode=query}, the default) or fragment. */
    private static String buildRedirect(String redirectUri, String responseMode, String code, String state) {
        StringBuilder location = new StringBuilder(redirectUri);
        boolean fragment = "fragment".equals(responseMode);
        location.append(fragment ? '#' : (redirectUri.contains("?") ? '&' : '?'));
        location.append("code=").append(urlEncode(code));
        if (state != null) {
            location.append("&state=").append(urlEncode(state));
        }
        return location.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ── Token endpoint ──────────────────────────────────────────────────────────

    private Response handleToken(AzureRequest request, String tenantSegment, String baseUrl, boolean v2) {
        Map<String, String> form = parseForm(request);
        String grantType = form.getOrDefault("grant_type", "");
        String clientId  = form.get("client_id");
        String scope     = form.getOrDefault("scope", form.get("resource"));

        String effectiveTenant = resolveTenantId(tenantSegment);
        AppRegistration app = clientId == null ? null
                : store.findAppByClientId(clientId).orElse(null);

        String appId = app != null ? app.appId() : (clientId != null ? clientId : EntraStore.DEV_CLIENT_ID);
        String oid   = app != null
                ? store.findServicePrincipalByAppId(appId).map(EntraModels.ServicePrincipal::objectId)
                       .orElse(app.objectId())
                : TokenIssuer.deterministicGuid(appId);

        String issuer = config.services().entra().issuer().orElse(
                v2 ? baseUrl + "/" + effectiveTenant + "/v2.0"
                   : baseUrl + "/" + effectiveTenant + "/");
        long lifetime = config.services().entra().tokenLifetimeSeconds();

        switch (grantType) {
            case "client_credentials" -> {
                String audience = audienceForClientCredentials(scope, baseUrl);
                String token = tokenIssuer.issue(new TokenIssuer.TokenSpec(
                        effectiveTenant, issuer, audience, oid, oid, appId, null,
                        v2 ? "2.0" : "1.0", "app", lifetime));
                return tokenResponse(token, lifetime, null);
            }
            case "password" -> {
                String username = form.getOrDefault("username", "dev-user@floci-az.local");
                String userOid  = TokenIssuer.deterministicGuid(username);
                String audience = v2 ? appId : firstScopeResource(scope, baseUrl);
                String scp = normalizeScopes(scope);
                String token = tokenIssuer.issue(new TokenIssuer.TokenSpec(
                        effectiveTenant, issuer, audience, userOid, userOid, appId, scp,
                        v2 ? "2.0" : "1.0", null, lifetime));
                return tokenResponse(token, lifetime, scp);
            }
            case "authorization_code" -> {
                return handleAuthorizationCodeGrant(form, clientId, appId, effectiveTenant, issuer, v2,
                        lifetime, baseUrl);
            }
            default -> {
                return oauthError("unsupported_grant_type",
                        "grant_type '" + grantType + "' is not supported in this phase", 400);
            }
        }
    }

    /**
     * Redeems a single-use authorization code: verifies the {@code redirect_uri}/{@code client_id}
     * match the {@code /authorize} request and the PKCE {@code code_verifier}, then issues an access
     * token plus an ID token (echoing the original {@code nonce}) for the code's resolved user.
     */
    private Response handleAuthorizationCodeGrant(Map<String, String> form, String clientId, String appId,
            String effectiveTenant, String issuer, boolean v2, long lifetime, String baseUrl) {
        String code = form.get("code");
        if (code == null || code.isBlank()) {
            return oauthError("invalid_request", "code is required", 400);
        }
        Optional<AuthorizationCode> stored = store.consumeAuthorizationCode(code);
        if (stored.isEmpty()) {
            return oauthError("invalid_grant", "authorization code is invalid or has already been used", 400);
        }
        AuthorizationCode authCode = stored.get();
        if (authCode.expiresAt().isBefore(Instant.now())) {
            return oauthError("invalid_grant", "authorization code has expired", 400);
        }
        String redirectUri = form.get("redirect_uri");
        if (redirectUri != null && !redirectUri.equals(authCode.redirectUri())) {
            return oauthError("invalid_grant", "redirect_uri does not match the authorization request", 400);
        }
        if (clientId != null && authCode.clientId() != null && !clientId.equals(authCode.clientId())) {
            return oauthError("invalid_grant", "client_id does not match the authorization request", 400);
        }
        if (!verifyPkce(authCode, form.get("code_verifier"))) {
            return oauthError("invalid_grant", "code_verifier does not match the code_challenge", 400);
        }
        User user = store.getUser(authCode.userObjectId()).orElse(null);
        if (user == null) {
            return oauthError("invalid_grant", "authorization code does not resolve to a known user", 400);
        }

        String scp = normalizeScopes(authCode.scope());
        String accessAudience = v2 ? appId : firstScopeResource(authCode.scope(), baseUrl);
        String accessToken = tokenIssuer.issue(new TokenIssuer.TokenSpec(
                effectiveTenant, issuer, accessAudience, user.objectId(), user.objectId(), appId, scp,
                v2 ? "2.0" : "1.0", null, lifetime));
        // The ID token audience is always the client id, regardless of API version — OIDC, not Azure-version-specific.
        String idToken = tokenIssuer.issueIdToken(new TokenIssuer.TokenSpec(
                effectiveTenant, issuer, appId, user.objectId(), user.objectId(), appId, scp,
                v2 ? "2.0" : "1.0", null, lifetime),
                authCode.nonce(), user.displayName(), user.upn(), user.email());
        return tokenResponse(accessToken, idToken, lifetime, scp);
    }

    /**
     * PKCE verification is permissive: an {@code /authorize} request that omitted
     * {@code code_challenge} skips verification here too, matching this phase's permissive-by-default
     * stance on client validation.
     */
    private boolean verifyPkce(AuthorizationCode authCode, String codeVerifier) {
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
            return false;
        }
    }

    /** Preserves the existing stub response shape, adding {@code scope} only when present. */
    private Response tokenResponse(String accessToken, long expiresIn, String scope) {
        return tokenResponse(accessToken, null, expiresIn, scope);
    }

    private Response tokenResponse(String accessToken, String idToken, long expiresIn, String scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token_type", "Bearer");
        body.put("expires_in", expiresIn);
        body.put("ext_expires_in", expiresIn);
        if (scope != null && !scope.isBlank()) {
            body.put("scope", scope);
        }
        body.put("access_token", accessToken);
        if (idToken != null) {
            body.put("id_token", idToken);
        }
        return json(body);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveTenantId(String tenantSegment) {
        return switch (tenantSegment) {
            case "common", "organizations", "consumers" -> config.services().entra().defaultTenantId();
            default -> tenantSegment;
        };
    }

    /** v2.0 client_credentials request scope is {@code {resource}/.default}; aud is the resource. */
    private String audienceForClientCredentials(String scope, String baseUrl) {
        if (scope == null || scope.isBlank()) {
            return baseUrl;
        }
        String first = scope.split("\\s+")[0];
        if (first.endsWith("/.default")) {
            return first.substring(0, first.length() - "/.default".length());
        }
        return first;
    }

    private String firstScopeResource(String scope, String baseUrl) {
        if (scope == null || scope.isBlank()) {
            return baseUrl;
        }
        return scope.split("\\s+")[0];
    }

    /** Strips OIDC reserved scopes so {@code scp} carries only resource scopes. */
    private String normalizeScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : scope.split("\\s+")) {
            if (s.equals("openid") || s.equals("profile") || s.equals("email")
                    || s.equals("offline_access")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private Map<String, String> parseForm(AzureRequest request) {
        Map<String, String> result = new HashMap<>();
        byte[] bytes;
        try {
            bytes = request.bodyStream() == null ? new byte[0] : request.bodyStream().readAllBytes();
        } catch (IOException e) {
            return result;
        }
        String body = new String(bytes, StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return result;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    /** First path segment is the tenant; defaults to {@code common} for safety. */
    private String tenantSegment(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    private static String stripSlashes(String path) {
        String p = path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private Response json(Object body) {
        try {
            return Response.ok(mapper.writeValueAsString(body)).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            return oauthError("server_error", "serialisation failed", 500);
        }
    }

    private static final DateTimeFormatter AAD_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /**
     * Emits the Entra-compatible OAuth error body. Azure embeds the {@code AADSTS} code plus
     * trace/correlation/timestamp inside {@code error_description} and repeats them as discrete
     * fields ({@code error_codes}, {@code trace_id}, {@code correlation_id}, {@code timestamp},
     * {@code error_uri}) — MSAL/azure-identity parse {@code error_codes} for retry decisions.
     */
    private Response oauthError(String code, String description, int status) {
        int aadsts = aadstsFor(code);
        String traceId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        String timestamp = AAD_TIMESTAMP.format(Instant.now());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("error_description", "AADSTS" + aadsts + ": " + description
                + "\r\nTrace ID: " + traceId
                + "\r\nCorrelation ID: " + correlationId
                + "\r\nTimestamp: " + timestamp);
        body.put("error_codes", List.of(aadsts));
        body.put("timestamp", timestamp);
        body.put("trace_id", traceId);
        body.put("correlation_id", correlationId);
        body.put("error_uri", "https://login.microsoftonline.com/error?code=" + aadsts);
        try {
            return Response.status(status).entity(mapper.writeValueAsString(body))
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            return Response.status(status).build();
        }
    }

    /** Maps an OAuth error code to a representative Azure {@code AADSTS} numeric code. */
    private static int aadstsFor(String code) {
        return switch (code) {
            case "unsupported_grant_type" -> 70003;
            case "invalid_client"         -> 70002;
            case "invalid_grant"          -> 70000;
            case "invalid_request"        -> 90014;
            case "server_error"           -> 50000;
            default                        -> 90014;
        };
    }
}
