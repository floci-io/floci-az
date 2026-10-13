package io.floci.az.services.entra;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * {@code GET /{tenant}/v2.0/.well-known/openid-configuration} and
 * {@code GET /{tenant}/.well-known/openid-configuration}: the OpenID Connect discovery document.
 */
@ApplicationScoped
public class DiscoveryEndpoint implements EntraEndpoint {

    private static final String PATH = ".well-known/openid-configuration";

    private final DiscoveryProvider discovery;
    private final EntraResponses responses;

    @Inject
    public DiscoveryEndpoint(DiscoveryProvider discovery, EntraResponses responses) {
        this.discovery = discovery;
        this.responses = responses;
    }

    @Override
    public boolean matches(String path) {
        return path.endsWith(PATH);
    }

    @Override
    public Response handle(EntraRequest request) {
        return responses.json(discovery.document(request));
    }
}
