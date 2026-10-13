package io.floci.az.services.entra;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.FormBody;
import io.floci.az.services.entra.EntraModels.AppRegistration;
import io.floci.az.services.entra.EntraModels.ServicePrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

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

    private final EmulatorConfig config;
    private final EntraStore store;
    private final IssuerFactory issuers;
    private final EntraResponses responses;
    private final Map<String, GrantHandler> grants;

    @Inject
    public TokenEndpoint(EmulatorConfig config, EntraStore store, IssuerFactory issuers,
                         EntraResponses responses, Instance<GrantHandler> grantHandlers) {
        this.config = config;
        this.store = store;
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

    @Override
    public boolean matches(String path) {
        return path.endsWith(V2_PATH) || path.endsWith(V1_PATH);
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
        String clientId = form.get("client_id");
        String scope = form.getOrDefault("scope", form.get("resource"));
        AppRegistration app = clientId == null ? null : store.findAppByClientId(clientId).orElse(null);

        String appId = app != null ? app.appId() : (clientId != null ? clientId : EntraStore.DEV_CLIENT_ID);
        String clientObjectId = app != null
                ? store.findServicePrincipalByAppId(appId).map(ServicePrincipal::objectId).orElse(app.objectId())
                : TokenIssuer.deterministicGuid(appId);

        return new TokenRequest(form, request.tenantId(), request.baseUrl(), v2, clientId, appId,
                clientObjectId, issuers.issuer(request.baseUrl(), request.tenantId(), v2), scope,
                config.services().entra().tokenLifetimeSeconds());
    }
}
