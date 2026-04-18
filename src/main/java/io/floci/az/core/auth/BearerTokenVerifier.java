package io.floci.az.core.auth;

import io.floci.az.core.AuthContext;
import io.floci.az.core.AuthType;
import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class BearerTokenVerifier implements AuthVerifier {
    @Override
    public Optional<AuthContext> verify(AzureRequest request) {
        String authHeader = request.headers().getHeaderString("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            // Accept any Bearer token in dev mode
            return Optional.of(new AuthContext(request.accountName(), AuthType.BEARER, true));
        }
        return Optional.empty();
    }
}
