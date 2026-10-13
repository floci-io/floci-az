package io.floci.az.services.entra;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

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
    public void advertise(EntraRequest request, Map<String, Object> document) {
        document.put("jwks_uri", request.tenantBase() + "/" + PATH);
        document.put("id_token_signing_alg_values_supported", List.of("RS256"));
    }

    @Override
    public Response handle(EntraRequest request) {
        return responses.json(keys.jwks());
    }
}
