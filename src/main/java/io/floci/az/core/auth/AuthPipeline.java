package io.floci.az.core.auth;

import io.floci.az.core.AuthContext;
import io.floci.az.core.AuthType;
import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;

@ApplicationScoped
public class AuthPipeline {
    private final Instance<AuthVerifier> verifiers;

    @Inject
    public AuthPipeline(Instance<AuthVerifier> verifiers) {
        this.verifiers = verifiers;
    }

    public AuthContext resolve(AzureRequest request) {
        for (AuthVerifier verifier : verifiers) {
            Optional<AuthContext> context = verifier.verify(request);
            if (context.isPresent()) {
                return context.get();
            }
        }
        // Fallback to anonymous
        return new AuthContext(request.accountName(), AuthType.ANONYMOUS, true);
    }
}
