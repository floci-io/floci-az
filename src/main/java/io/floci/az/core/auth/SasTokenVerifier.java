package io.floci.az.core.auth;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AuthContext;
import io.floci.az.core.AuthType;
import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Reads a SAS from the query and checks its time window. An account SAS to a storage service is also checked against
 * the account's keys: the key that signed it is recorded, and with {@code parity-overrides.strict-storage-keys} one
 * no current key signed is refused. The Blob service checks every SAS it serves, signature included, in
 * {@link StorageSasAuthorization}.
 */
@ApplicationScoped
public class SasTokenVerifier implements AuthVerifier {

    private final StorageAccountKeys keys;
    private final EmulatorConfig config;

    @Inject
    public SasTokenVerifier(StorageAccountKeys keys, EmulatorConfig config) {
        this.keys = keys;
        this.config = config;
    }

    @Override
    public Optional<AuthContext> verify(AzureRequest request) {
        return StorageSasToken.from(request.queryParams())
            .map(sas -> {
                Instant now = Instant.now();
                boolean valid = true;
                if (sas.startTime() != null) {
                    valid = sas.parsedStartTime().map(OffsetDateTime::toInstant)
                            .filter(start -> !start.isAfter(now))
                            .isPresent();
                }
                if (sas.expiryTime() != null) {
                    valid = valid && sas.parsedExpiryTime().map(OffsetDateTime::toInstant)
                            .filter(expiry -> expiry.isAfter(now))
                            .isPresent();
                }
                if (!sas.isAccountSas() || !SharedKeyAuthVerifier.STORAGE_SERVICES.contains(request.serviceType())
                        || request.accountName() == null) {
                    return new AuthContext(request.accountName(), AuthType.SAS, valid, Optional.of(sas));
                }
                String stringToSign = sas.accountStringToSign(request.accountName());
                Optional<String> signer = keys.signer(request.accountName(), stringToSign, sas.signature());
                Optional<String> failure = signer.isEmpty() && config.strictStorageKeys()
                        ? Optional.of(StorageSasAuthorization.signatureMismatch(stringToSign))
                        : Optional.empty();
                return new AuthContext(request.accountName(), AuthType.SAS, valid && failure.isEmpty(),
                        Optional.of(sas), signer, failure);
            });
    }
}
