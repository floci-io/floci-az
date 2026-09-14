package io.floci.az.services.acr;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.FormBody;
import io.floci.az.services.entra.TokenIssuer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Azure Container Registry's Entra token exchange: the two {@code /oauth2/} endpoints that
 * {@code az acr login} and the Azure SDK container-registry clients use instead of a plain Docker
 * login.
 *
 * <pre>
 *   GET  /v2/                 → 401 + Bearer challenge naming the realm and service
 *   POST /oauth2/exchange     → Entra access token  → ACR refresh token
 *   POST /oauth2/token        → ACR refresh token   → scoped ACR access token
 *   GET  /oauth2/token        → the Docker Registry token endpoint form (service + scope)
 * </pre>
 *
 * <p>Both tokens are RS256 JWTs minted by the emulator's {@link TokenIssuer}, so clients that
 * decode them keep working: the Azure CLI reads the access token's {@code access} claim when it
 * verifies permissions. <b>Tokens are issued, not verified</b>, and the scopes they carry are not
 * enforced at the data plane. See {@code docs/services/acr.md}.</p>
 */
@ApplicationScoped
public class AcrTokenService {

    /** The username Docker logs in with when the password is an ACR token. */
    public static final String TOKEN_USERNAME = "00000000-0000-0000-0000-000000000000";

    private static final String ISSUER = "Azure Container Registry";
    /** The grant types the clients send to the exchange endpoint, in the order they are documented. */
    private static final List<String> EXCHANGE_GRANTS =
            List.of("access_token", "refresh_token", "access_token_refresh_token");

    private final EmulatorConfig config;
    private final TokenIssuer tokenIssuer;

    @Inject
    public AcrTokenService(EmulatorConfig config, TokenIssuer tokenIssuer) {
        this.config = config;
        this.tokenIssuer = tokenIssuer;
    }

    /**
     * One entry of an access token's {@code access} claim: what the client asked for, echoed back
     * with the bare repository name it used.
     */
    public record Access(String type, String name, List<String> actions) {

        Map<String, Object> toClaim() {
            Map<String, Object> claim = new LinkedHashMap<>();
            claim.put("type", type);
            claim.put("name", name);
            claim.put("actions", actions);
            return claim;
        }
    }

    // ── Challenge ────────────────────────────────────────────────────────────────

    /**
     * The {@code WWW-Authenticate} value that starts the flow. The client parses {@code realm} and
     * {@code service} out of it and fails with a connectivity error if either is missing.
     *
     * <p>The realm carries the scheme the caller actually used. TLS is off by default here, and a
     * realm hardcoded to {@code https://} would send the client to a port nothing is listening on.
     * {@code service} is always the login server itself, as in Azure.</p>
     */
    public static String challenge(String scheme, String loginServer) {
        return "Bearer realm=\"" + scheme + "://" + loginServer + "/oauth2/token\""
                + ",service=\"" + loginServer + "\"";
    }

    /** {@code GET /v2/} answered before authentication: {@code 401} plus the bearer challenge. */
    public static Response challengeResponse(String scheme, String loginServer) {
        return Response.status(401)
                .header("WWW-Authenticate", challenge(scheme, loginServer))
                .header("Docker-Distribution-Api-Version", "registry/2.0")
                .entity(Map.of("errors", List.of(Map.of(
                        "code", "UNAUTHORIZED",
                        "message", "authentication required"))))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    // ── Scope parsing ────────────────────────────────────────────────────────────

    /**
     * Parses one scope string into an access entry. The Azure CLI sends
     * {@code repository:{name}:{actions}}, {@code artifact-repository:{name}:{actions}} or
     * {@code registry:{permission}:*}; Docker sends the same repository form. Actions are comma
     * separated. A repository name may itself contain {@code /} but never {@code :}, so the first
     * and last separators bound the name.
     */
    public static Optional<Access> parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return Optional.empty();
        }
        int firstColon = scope.indexOf(':');
        int lastColon = scope.lastIndexOf(':');
        if (firstColon <= 0 || lastColon <= firstColon || lastColon == scope.length() - 1) {
            return Optional.empty();
        }
        String type = scope.substring(0, firstColon);
        String name = scope.substring(firstColon + 1, lastColon);
        List<String> actions = Arrays.stream(scope.substring(lastColon + 1).split(","))
                .map(String::trim)
                .filter(action -> !action.isEmpty())
                .toList();
        if (name.isEmpty() || actions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Access(type, name, actions));
    }

    /** Parses every scope in a request: whitespace separates scopes, and the parameter may repeat. */
    public static List<Access> parseScopes(List<String> scopes) {
        List<Access> access = new ArrayList<>();
        if (scopes == null) {
            return access;
        }
        for (String scope : scopes) {
            if (scope == null) {
                continue;
            }
            for (String single : scope.split("\\s+")) {
                parseScope(single).ifPresent(access::add);
            }
        }
        return access;
    }

    // ── Token building ───────────────────────────────────────────────────────────

    /**
     * An ACR refresh token: audience is the login server, tenant is the emulator's. It carries no
     * {@code access} claim; it is exchanged for scoped access tokens at the token endpoint.
     */
    public String refreshToken(String loginServer) {
        return tokenIssuer.issue(spec(loginServer), Map.of("grant_type", "refresh_token"));
    }

    /**
     * A scoped ACR access token: the {@code access} claim carries one entry per requested scope,
     * holding the type, the bare name the client asked for, and its actions.
     */
    public String accessToken(String loginServer, List<Access> access, String grantType) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("access", access.stream().map(Access::toClaim).toList());
        if (grantType != null) {
            claims.put("grant_type", grantType);
        }
        return tokenIssuer.issue(spec(loginServer), claims);
    }

    private TokenIssuer.TokenSpec spec(String loginServer) {
        String tenantId = config.services().entra().defaultTenantId();
        String subject = TokenIssuer.deterministicGuid("acr:" + loginServer);
        return new TokenIssuer.TokenSpec(tenantId, ISSUER, loginServer, subject, subject,
                TOKEN_USERNAME, null, "1.0", null, lifetimeSeconds());
    }

    private long lifetimeSeconds() {
        return config.services().entra().tokenLifetimeSeconds();
    }

    // ── Endpoints ────────────────────────────────────────────────────────────────

    /**
     * {@code POST /oauth2/exchange}: trades an Entra access token for an ACR refresh token. The
     * Entra token is accepted without verification, so only the request shape is checked.
     */
    public Response handleExchange(AzureRequest request, String loginServer) {
        if (!"POST".equals(request.method())) {
            return AcrErrors.error(405, AcrErrors.UNSUPPORTED,
                    "the exchange endpoint accepts POST only");
        }
        Map<String, String> form = FormBody.parse(request.bodyStream());
        String grantType = form.getOrDefault("grant_type", "");
        if (!EXCHANGE_GRANTS.contains(grantType)) {
            return AcrErrors.badRequest("grant_type '" + grantType + "' is not supported at the "
                    + "exchange endpoint; expected one of " + String.join(", ", EXCHANGE_GRANTS));
        }
        if (isBlank(form.get("service"))) {
            return AcrErrors.badRequest("service is required");
        }
        return Response.ok(Map.of("refresh_token", refreshToken(loginServer)))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /**
     * {@code /oauth2/token}: {@code POST} with {@code grant_type=refresh_token} for the Entra flow
     * or {@code grant_type=password} for admin credentials, and {@code GET} with {@code service}
     * and {@code scope} query parameters for Docker's own client.
     */
    public Response handleToken(AzureRequest request, String loginServer) {
        return switch (request.method()) {
            // Docker's own client sends no grant type, only service and scope.
            case "GET" -> accessTokenResponse(loginServer,
                    parseScopes(request.queryParamsMulti().get("scope")), null);
            case "POST" -> handleTokenPost(request, loginServer);
            default -> AcrErrors.error(405, AcrErrors.UNSUPPORTED,
                    "the token endpoint accepts GET and POST only");
        };
    }

    private Response handleTokenPost(AzureRequest request, String loginServer) {
        Map<String, String> form = FormBody.parse(request.bodyStream());
        String grantType = form.getOrDefault("grant_type", "");
        switch (grantType) {
            case "refresh_token" -> {
                String refreshToken = form.get("refresh_token");
                if (isBlank(refreshToken)) {
                    return AcrErrors.badRequest("refresh_token is required");
                }
                // Nothing verifies the token, but a token that is not even a JWT is rejected so
                // clients still exercise the re-authentication path they would take against Azure.
                if (!looksLikeJwt(refreshToken)) {
                    return AcrErrors.error(401, AcrErrors.UNAUTHORIZED, "the refresh token is malformed");
                }
            }
            case "password" -> {
                if (isBlank(form.get("password"))) {
                    return AcrErrors.badRequest("password is required");
                }
            }
            default -> {
                return AcrErrors.badRequest("grant_type '" + grantType + "' is not supported at the "
                        + "token endpoint; expected refresh_token or password");
            }
        }
        if (isBlank(form.get("service"))) {
            return AcrErrors.badRequest("service is required");
        }
        return accessTokenResponse(loginServer,
                parseScopes(List.of(form.getOrDefault("scope", ""))), grantType);
    }

    private Response accessTokenResponse(String loginServer, List<Access> access, String grantType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", accessToken(loginServer, access, grantType));
        body.put("expires_in", lifetimeSeconds());
        body.put("issued_at", DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().truncatedTo(ChronoUnit.MILLIS)));
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** A compact JWT: three non-empty dot-separated segments. */
    private static boolean looksLikeJwt(String token) {
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3) {
            return false;
        }
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
