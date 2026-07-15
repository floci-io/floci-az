package io.floci.az.core;

import io.floci.az.core.auth.StorageSasToken;
import java.util.Optional;

public record AuthContext(
    String accountName,
    AuthType type,
    boolean isValid,
    Optional<StorageSasToken> storageSas
) {
    public AuthContext(String accountName, AuthType type, boolean isValid) {
        this(accountName, type, isValid, Optional.empty());
    }
}
