package io.floci.az.core.auth;

import io.floci.az.core.AuthContext;
import io.floci.az.core.AuthType;
import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

@ApplicationScoped
public class SasTokenVerifier implements AuthVerifier {
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
                return new AuthContext(request.accountName(), AuthType.SAS, valid, Optional.of(sas));
            });
    }
}
