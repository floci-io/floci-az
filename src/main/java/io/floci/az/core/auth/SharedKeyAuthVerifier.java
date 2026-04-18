package io.floci.az.core.auth;

import io.floci.az.core.AuthContext;
import io.floci.az.core.AuthType;
import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class SharedKeyAuthVerifier implements AuthVerifier {
    @Override
    public Optional<AuthContext> verify(AzureRequest request) {
        String authHeader = request.headers().getHeaderString("Authorization");
        if (authHeader != null && authHeader.startsWith("SharedKey ")) {
            // SharedKey {accountName}:{signature}
            String val = authHeader.substring(10);
            int colonIndex = val.indexOf(':');
            if (colonIndex != -1) {
                String accountName = val.substring(0, colonIndex);
                // In dev mode, we accept any signature
                return Optional.of(new AuthContext(accountName, AuthType.SHARED_KEY, true));
            }
        }
        return Optional.empty();
    }
}
