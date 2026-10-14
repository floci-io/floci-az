package io.floci.az.services.entra;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.RequestUrls;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Microsoft Entra ID (Azure AD) emulation: a local OpenID Connect provider.
 *
 * <p>Tenant-rooted paths arriving at the ARM base URL (port 4577), each served by an
 * {@link EntraEndpoint}:
 * <ul>
 *   <li>{@code {tenant}/oauth2/v2.0/token} and {@code {tenant}/oauth2/token}: {@link TokenEndpoint}</li>
 *   <li>{@code {tenant}/oauth2/v2.0/authorize}: {@link AuthorizeEndpoint}</li>
 *   <li>{@code {tenant}/discovery/v2.0/keys}: {@link JwksEndpoint}</li>
 *   <li>{@code {tenant}/v2.0/.well-known/openid-configuration} and
 *       {@code {tenant}/.well-known/openid-configuration}: {@link DiscoveryEndpoint}</li>
 * </ul>
 * where {@code tenant} may be a tenant id or {@code common}/{@code organizations}/{@code consumers}.
 *
 * <p>Client credentials are not verified: any {@code client_id} and secret get a token.
 */
@ApplicationScoped
public class EntraServiceHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(EntraServiceHandler.class);

    private final EmulatorConfig config;
    private final Instance<EntraEndpoint> endpoints;
    private final EntraResponses responses;
    private final TenantResolver tenants;

    @Inject
    public EntraServiceHandler(EmulatorConfig config, Instance<EntraEndpoint> endpoints,
                               EntraResponses responses, TenantResolver tenants) {
        this.config = config;
        this.endpoints = endpoints;
        this.responses = responses;
        this.tenants = tenants;
    }

    @Override public String getServiceType() { return "entra"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().entra().enabled();
    }

    @Override
    public Response handle(AzureRequest request) {
        String path = stripSlashes(request.resourcePath());
        LOG.infof("EntraService: %s %s", request.method(), path);

        String tenantSegment = tenantSegment(path);
        EntraRequest entraRequest = new EntraRequest(request, path, tenantSegment,
                tenants.resolve(tenantSegment, null), RequestUrls.resolveBaseUrl(request, config));
        for (EntraEndpoint endpoint : endpoints) {
            if (endpoint.matches(path)) {
                return endpoint.handle(entraRequest);
            }
        }
        return responses.error(AadstsError.UNSUPPORTED_ENDPOINT, path);
    }

    /** The first path segment is the tenant. */
    private static String tenantSegment(String path) {
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
}
