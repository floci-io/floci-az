package io.floci.az.core.auth;

import io.floci.az.core.AuthContext;
import io.floci.az.core.AuthType;
import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

@ApplicationScoped
public class SasTokenParser implements AuthVerifier {
    @Override
    public Optional<AuthContext> verify(AzureRequest request) {
        if (request.queryParams().containsKey("sig") && request.queryParams().containsKey("sv")) {
            String signedExpiry = request.queryParams().get("se");
            if (signedExpiry != null) {
                try {
                    String decodedExpiry = URLDecoder.decode(signedExpiry, StandardCharsets.UTF_8);
                    Instant expiry = OffsetDateTime.parse(decodedExpiry).toInstant();
                    if (!expiry.isAfter(Instant.now())) {
                        return Optional.of(new AuthContext(request.accountName(), AuthType.SAS, false));
                    }
                } catch (DateTimeParseException e) {
                    return Optional.of(new AuthContext(request.accountName(), AuthType.SAS, false));
                }
            }
            // Accept any sig in dev mode
            return Optional.of(new AuthContext(request.accountName(), AuthType.SAS, true));
        }
        return Optional.empty();
    }
}
