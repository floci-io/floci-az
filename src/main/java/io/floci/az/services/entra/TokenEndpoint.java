package io.floci.az.services.entra;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.FormBody;
import io.floci.az.services.entra.DirectoryReader.DirectoryApplication;
import io.floci.az.services.entra.DirectoryReader.DirectoryServicePrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code POST /{tenant}/oauth2/v2.0/token} and the v1.0 {@code POST /{tenant}/oauth2/token}.
 * Resolves what every grant shares (tenant, client, issuer, lifetime) and dispatches on
 * {@code grant_type} to the registered {@link GrantHandler}s; any other grant type is answered
 * with {@link AadstsError#UNSUPPORTED_GRANT_TYPE}.
 */
@ApplicationScoped
public class TokenEndpoint implements EntraEndpoint {

    private static final String V2_PATH = "oauth2/v2.0/token";
    private static final String V1_PATH = "oauth2/token";
    private static final String BASIC_SCHEME = "Basic ";
    private static final Logger LOG = Logger.getLogger(TokenEndpoint.class);

    /**
     * Client authentication methods the endpoint accepts. Credentials are not verified yet, so a
     * secret in the form, a secret in HTTP Basic and a {@code client_assertion} are all accepted;
     * the client is identified from the form, or from the HTTP Basic user when the form names none.
     */
    private static final List<String> AUTH_METHODS =
            List.of("client_secret_post", "client_secret_basic", "private_key_jwt");

    private final EmulatorConfig config;
    private final DirectoryReader directory;
    private final IssuerFactory issuers;
    private final EntraResponses responses;
    private final Map<String, GrantHandler> grants;

    @Inject
    public TokenEndpoint(EmulatorConfig config, DirectoryReader directory, IssuerFactory issuers,
                         EntraResponses responses, Instance<GrantHandler> grantHandlers) {
        this.config = config;
        this.directory = directory;
        this.issuers = issuers;
        this.responses = responses;
        this.grants = indexByGrantType(grantHandlers);
    }

    private static Map<String, GrantHandler> indexByGrantType(Iterable<GrantHandler> handlers) {
        Map<String, GrantHandler> byType = new TreeMap<>();
        for (GrantHandler handler : handlers) {
            GrantHandler previous = byType.put(handler.grantType(), handler);
            if (previous != null) {
                throw new IllegalStateException("Two Entra grant handlers claim grant_type '"
                        + handler.grantType() + "'");
            }
        }
        return byType;
    }

    /** The registered grant types, in a stable (sorted) order. */
    public List<String> grantTypes() {
        return List.copyOf(grants.keySet());
    }

    @Override
    public boolean matches(String path) {
        return path.endsWith(V2_PATH) || path.endsWith(V1_PATH);
    }

    @Override
    public void advertise(EntraRequest request, Map<String, Object> document) {
        document.put("token_endpoint", request.tenantBase() + "/" + V2_PATH);
        document.put("token_endpoint_auth_methods_supported", AUTH_METHODS);
        document.put("grant_types_supported", grantTypes());
    }

    @Override
    public Response handle(EntraRequest request) {
        Map<String, String> form = FormBody.parse(request.request().bodyStream());
        String grantType = form.getOrDefault("grant_type", "");
        GrantHandler grant = grants.get(grantType);
        if (grant == null) {
            return responses.error(AadstsError.UNSUPPORTED_GRANT_TYPE, grantType);
        }
        return grant.handle(tokenRequest(request, form));
    }

    private TokenRequest tokenRequest(EntraRequest request, Map<String, String> form) {
        boolean v2 = request.path().endsWith(V2_PATH);
        String clientId = form.containsKey("client_id") ? form.get("client_id") : basicClientId(request);
        String scope = form.getOrDefault("scope", form.get("resource"));
        String directoryTenantId = request.directoryTenantId();
        DirectoryApplication app = clientId == null
                ? null
                : directory.applicationByAppId(directoryTenantId, clientId).orElse(null);

        String appId = app != null ? app.appId() : (clientId != null ? clientId : EntraStore.DEV_CLIENT_ID);
        String clientObjectId = app != null
                ? directory.servicePrincipalByAppId(directoryTenantId, appId).map(DirectoryServicePrincipal::id)
                        .orElse(app.id())
                : TokenIssuer.deterministicGuid(appId);

        return new TokenRequest(form, request.tenantId(), directoryTenantId, request.baseUrl(), v2, clientId, appId,
                clientObjectId, issuers.issuer(request.baseUrl(), request.tenantId(), v2), scope,
                config.services().entra().tokenLifetimeSeconds());
    }

    /**
     * The client id of an {@code Authorization: Basic} header ({@code client_secret_basic}, RFC 6749
     * section 2.3.1: base64 of the form-encoded id, a colon and the form-encoded secret), or null
     * when the request carries no such header or it does not decode.
     */
    private static String basicClientId(EntraRequest request) {
        HttpHeaders headers = request.request().headers();
        String authorization = headers == null ? null : headers.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authorization == null
                || !authorization.regionMatches(true, 0, BASIC_SCHEME, 0, BASIC_SCHEME.length())) {
            return null;
        }
        try {
            String credentials = new String(
                    Base64.getDecoder().decode(authorization.substring(BASIC_SCHEME.length()).trim()),
                    StandardCharsets.UTF_8);
            int colon = credentials.indexOf(':');
            String clientId = URLDecoder.decode(colon < 0 ? credentials : credentials.substring(0, colon),
                    StandardCharsets.UTF_8);
            return clientId.isEmpty() ? null : clientId;
        } catch (IllegalArgumentException e) {
            LOG.debugv("Entra: ignoring an Authorization: Basic header that does not decode: {0}", e.getMessage());
            return null;
        }
    }
}
