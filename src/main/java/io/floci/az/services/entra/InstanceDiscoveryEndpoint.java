package io.floci.az.services.entra;

import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.ScopeResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code GET /common/discovery/instance?api-version=1.1&authorization_endpoint={authorize url}}: instance discovery.
 *
 * <p>MSAL Go and MSAL4J call it on a known authority host before each token request, MSAL Python and msal-js on an
 * unknown one; all of them fall back to building the authority themselves when it fails. The answer is the shape
 * MSAL reads ({@code AadInstanceDiscoveryResponse} in MSAL4J, {@code tenant_discovery_endpoint} in MSAL Python and
 * msal-js): the tenant's OpenID configuration on the request's own base URL, and one metadata entry for the request
 * host. Azure lists every public cloud there, but {@code preferred_network} is the host MSAL then sends its token
 * requests to, so the entry names the emulator's host (and port, when the request carried one), with the sibling
 * authority hosts of its catalog cloud as aliases. Azure's {@code invalid_instance} error for an unknown authority is not modelled.
 */
@ApplicationScoped
public class InstanceDiscoveryEndpoint implements EntraEndpoint {

    private static final String API_VERSION = "1.1";
    private static final String DEFAULT_TENANT = "common";
    private static final String V2_AUTHORIZE_SUFFIX = "/oauth2/v2.0/authorize";

    private final EntraResponses responses;

    @Inject
    public InstanceDiscoveryEndpoint(EntraResponses responses) {
        this.responses = responses;
    }

    @Override
    public boolean matches(String path) {
        return ScopeResolver.isInstanceDiscoveryPath(path);
    }

    @Override
    public Response handle(EntraRequest request) {
        String authorizeEndpoint = request.request().queryParams().get("authorization_endpoint");
        URI base = URI.create(request.baseUrl());
        String authority = authorityOf(base);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("preferred_network", authority);
        entry.put("preferred_cache", authority);
        entry.put("aliases", List.copyOf(aliases(authority, base.getHost())));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant_discovery_endpoint", tenantDiscoveryEndpoint(request.baseUrl(), authorizeEndpoint));
        body.put("api-version", API_VERSION);
        body.put("metadata", List.of(entry));
        return responses.json(body);
    }

    /**
     * {@code {base}/{tenant}/v2.0/.well-known/openid-configuration} for a v2 authorize URL, without the
     * {@code v2.0} segment for a v1 one; the tenant is the authorize URL's first path segment.
     */
    static String tenantDiscoveryEndpoint(String baseUrl, String authorizeEndpoint) {
        String tenant = DEFAULT_TENANT;
        boolean v2 = true;
        if (authorizeEndpoint != null && !authorizeEndpoint.isBlank()) {
            String path = pathOf(authorizeEndpoint);
            String[] segments = path.split("/");
            for (String segment : segments) {
                if (!segment.isEmpty()) {
                    tenant = segment;
                    break;
                }
            }
            v2 = path.endsWith(V2_AUTHORIZE_SUFFIX);
        }
        return baseUrl + "/" + tenant + (v2 ? "/v2.0" : "") + "/.well-known/openid-configuration";
    }

    /** The request's own authority, then the other authority hosts of the catalog cloud that lists its host, if one does. */
    private static Set<String> aliases(String authority, String host) {
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(authority);
        for (CloudProfile cloud : CloudCatalog.all()) {
            List<String> authorityHosts = cloud.endpoints().authorityHosts();
            if (host != null && authorityHosts.stream().anyMatch(known -> known.equalsIgnoreCase(host))) {
                for (String known : authorityHosts) {
                    aliases.add(known.toLowerCase(Locale.ROOT));
                }
            }
        }
        return aliases;
    }

    private static String pathOf(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null ? "" : path;
        } catch (IllegalArgumentException ignored) {
            // Not a URL: MSAL always sends one, so read whatever was sent as the path itself.
            return url;
        }
    }

    /**
     * The host, with the port when the request carried one: msal-js puts {@code preferred_network} in place of its
     * authority's host and port ({@code Authority.resolveEndpointsAsync}), so a dropped port would send it elsewhere.
     */
    private static String authorityOf(URI base) {
        String host = base.getHost();
        if (host == null) {
            return base.toString();
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return base.getPort() < 0 ? lower : lower + ":" + base.getPort();
    }
}
