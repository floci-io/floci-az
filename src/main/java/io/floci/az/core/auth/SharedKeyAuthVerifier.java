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
        return accountName(authHeader)
                .map(accountName -> new AuthContext(accountName, AuthType.SHARED_KEY, true));
    }

    /** Account identity carried by Azure Storage's {@code SharedKey account:signature} header. */
    public static Optional<String> accountName(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("SharedKey ")) {
            return Optional.empty();
        }
        String value = authHeader.substring(10);
        int colonIndex = value.indexOf(':');
        return colonIndex == -1 ? Optional.empty() : Optional.of(value.substring(0, colonIndex));
    }
}
