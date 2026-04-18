package io.floci.az.core.auth;

import io.floci.az.core.AuthContext;
import io.floci.az.core.AuthType;
import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class SasTokenParser implements AuthVerifier {
    @Override
    public Optional<AuthContext> verify(AzureRequest request) {
        if (request.queryParams().containsKey("sig") && request.queryParams().containsKey("sv")) {
            // Accept any sig in dev mode
            return Optional.of(new AuthContext(request.accountName(), AuthType.SAS, true));
        }
        return Optional.empty();
    }
}
