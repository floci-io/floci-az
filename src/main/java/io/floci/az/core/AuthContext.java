package io.floci.az.core;

import io.floci.az.core.auth.StorageSasToken;

import java.util.Optional;

/**
 * Who a request says it is, as the auth pipeline read it.
 *
 * @param signingKey    for a Shared Key, Shared Key Lite or account SAS request, the account key ({@code key1} or
 *                      {@code key2}) whose signature it carries; empty when no current key signed it
 * @param failureDetail when {@code isValid} is false, Azure's {@code AuthenticationErrorDetail} for the refusal
 */
public record AuthContext(
    String accountName,
    AuthType type,
    boolean isValid,
    Optional<StorageSasToken> storageSas,
    Optional<String> signingKey,
    Optional<String> failureDetail
) {
    public AuthContext(String accountName, AuthType type, boolean isValid) {
        this(accountName, type, isValid, Optional.empty());
    }

    public AuthContext(String accountName, AuthType type, boolean isValid, Optional<StorageSasToken> storageSas) {
        this(accountName, type, isValid, storageSas, Optional.empty(), Optional.empty());
    }
}
