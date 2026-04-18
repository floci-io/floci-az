package io.floci.az.core.auth;

import io.floci.az.core.AuthContext;
import io.floci.az.core.AzureRequest;
import java.util.Optional;

public interface AuthVerifier {
    Optional<AuthContext> verify(AzureRequest request);
}
