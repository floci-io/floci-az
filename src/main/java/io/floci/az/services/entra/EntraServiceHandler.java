package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.services.entra.EntraModels.AppRegistration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Override public boolean canHandle(AzureRequest request) {
        return "entra".equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest request) {
        String path = stripSlashes(request.resourcePath());
        String baseUrl = resolveBaseUrl(request);
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
        return oauthError("invalid_request", "Unsupported Entra endpoint: " + path, 404);
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
                : deterministicGuid(appId);

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
                String userOid  = deterministicGuid(username);
                String audience = v2 ? appId : firstScopeResource(scope, baseUrl);
                String scp = normalizeScopes(scope);
                String token = tokenIssuer.issue(new TokenIssuer.TokenSpec(
                        effectiveTenant, issuer, audience, userOid, userOid, appId, scp,
                        v2 ? "2.0" : "1.0", null, lifetime));
                return tokenResponse(token, lifetime, scp);
            }
            default -> {
                return oauthError("unsupported_grant_type",
                        "grant_type '" + grantType + "' is not supported in this phase", 400);
            }
        }
    }

    /** Preserves the existing stub response shape, adding {@code scope} only when present. */
    private Response tokenResponse(String accessToken, long expiresIn, String scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token_type", "Bearer");
        body.put("expires_in", expiresIn);
        body.put("ext_expires_in", expiresIn);
        if (scope != null && !scope.isBlank()) {
            body.put("scope", scope);
        }
        body.put("access_token", accessToken);
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

    private String resolveBaseUrl(AzureRequest request) {
        String host = request.headers() == null ? null : request.headers().getHeaderString("Host");
        if (host == null || host.isBlank()) {
            return config.effectiveBaseUrl();
        }
        String scheme = request.secure() ? "https" : "http";
        return scheme + "://" + host;
    }

    /** Stable GUID derived from an arbitrary identity string, for synthetic oid claims. */
    private static String deterministicGuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
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
