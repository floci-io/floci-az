package io.floci.az.services.entra;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** {@code GET /{tenant}/discovery/v2.0/keys}: the JWKS holding the token signing key. */
@ApplicationScoped
public class JwksEndpoint implements EntraEndpoint {

    private static final String PATH = "discovery/v2.0/keys";

    private final SigningKeyProvider keys;
    private final EntraResponses responses;

    @Inject
    public JwksEndpoint(SigningKeyProvider keys, EntraResponses responses) {
        this.keys = keys;
        this.responses = responses;
    }

    @Override
    public boolean matches(String path) {
        return path.endsWith(PATH);
    }

    @Override
    public Response handle(EntraRequest request) {
        return responses.json(keys.jwks());
    }
}
