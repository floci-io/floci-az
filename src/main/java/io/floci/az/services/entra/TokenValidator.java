package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Decides whether a Bearer token is one this emulator issued and still honours: RS256, signed by the Entra signing
 * key, within its lifetime, and with an issuer that belongs to its own tenant.
 *
 * <p>The issuer's host is not compared. {@link IssuerFactory} derives it from the URL the client signed in through,
 * so one client's tokens legitimately carry {@code http://floci-az:4577} or {@code https://localhost:4577}; the
 * signature is what proves the emulator issued the token. When {@code entra.issuer} is configured every token
 * carries exactly that value, so it is compared exactly.
 */
@ApplicationScoped
public class TokenValidator {

    /** Why a token was refused, which decides the code a service answers with. */
    public enum Reason { MISSING, INVALID, AUDIENCE }

    /** A refused token: the reason and a message for the response body. */
    public record Rejection(Reason reason, String message) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BEARER = "Bearer ";
    private static final String ALGORITHM = "RS256";
    private static final String JCA_ALGORITHM = "SHA256withRSA";
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final Supplier<RSAPublicKey> publicKey;
    private final Supplier<String> kid;
    private final String configuredIssuer;
    private final Clock clock;

    @Inject
    public TokenValidator(SigningKeyProvider keys, EmulatorConfig config) {
        this(keys::publicKey, keys::kid, config.services().entra().issuer().orElse(null), Clock.systemUTC());
    }

    TokenValidator(Supplier<RSAPublicKey> publicKey, Supplier<String> kid, String configuredIssuer, Clock clock) {
        this.publicKey = publicKey;
        this.kid = kid;
        this.configuredIssuer = configuredIssuer;
        this.clock = clock;
    }

    /**
     * Checks the token in an {@code Authorization} header value.
     *
     * @param authorization    the header value, or null when the request has none
     * @param acceptedAudience whether the caller's service accepts a token's {@code aud}
     * @return why the token is refused, or empty when it is accepted
     */
    public Optional<Rejection> validate(String authorization, Predicate<String> acceptedAudience) {
        if (authorization == null || authorization.isBlank()) {
            return rejected(Reason.MISSING, "The authentication failed because of missing 'Authorization' header.");
        }
        if (!authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return invalid("The 'Authorization' header does not carry a Bearer token.");
        }
        String[] parts = authorization.substring(BEARER.length()).trim().split("\\.", -1);
        if (parts.length != 3) {
            return invalid("The access token is not a JSON Web Token.");
        }
        try {
            JsonNode header = decode(parts[0]);
            JsonNode claims = decode(parts[1]);
            if (!ALGORITHM.equals(header.path("alg").asText()) || !kid.get().equals(header.path("kid").asText())) {
                return invalid("The access token was not signed by this emulator's signing key.");
            }
            if (!signatureMatches(parts)) {
                return invalid("The access token signature could not be verified.");
            }
            return lifetimeRejection(claims)
                .or(() -> issuerRejection(claims))
                .or(() -> audienceRejection(claims, acceptedAudience));
        } catch (IllegalArgumentException | IOException expected) {
            // Not base64url or not JSON: what any client-supplied garbage looks like, so it is an answer, not a fault.
            return invalid("The access token is not a JSON Web Token.");
        } catch (GeneralSecurityException expected) {
            // A signature of the wrong length lands here rather than in a false verify.
            return invalid("The access token signature could not be verified.");
        }
    }

    private static JsonNode decode(String part) throws IOException {
        return MAPPER.readTree(Base64.getUrlDecoder().decode(part));
    }

    private boolean signatureMatches(String[] parts) throws GeneralSecurityException {
        Signature signature = Signature.getInstance(JCA_ALGORITHM);
        signature.initVerify(publicKey.get());
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        return signature.verify(Base64.getUrlDecoder().decode(parts[2]));
    }

    private Optional<Rejection> lifetimeRejection(JsonNode claims) {
        Instant now = clock.instant();
        if (!claims.path("exp").isNumber()) {
            return invalid("The access token has no expiry.");
        }
        Instant expires = Instant.ofEpochSecond(claims.path("exp").asLong());
        if (now.isAfter(expires.plus(CLOCK_SKEW))) {
            return invalid("The access token expired at " + expires + ".");
        }
        if (claims.path("nbf").isNumber()) {
            Instant notBefore = Instant.ofEpochSecond(claims.path("nbf").asLong());
            if (now.plus(CLOCK_SKEW).isBefore(notBefore)) {
                return invalid("The access token is not valid before " + notBefore + ".");
            }
        }
        return Optional.empty();
    }

    private Optional<Rejection> issuerRejection(JsonNode claims) {
        String tenant = claims.path("tid").asText("");
        if (tenant.isBlank()) {
            return invalid("The access token names no tenant.");
        }
        String issuer = claims.path("iss").asText("");
        return issuerMatches(issuer, tenant) ? Optional.empty()
            : invalid("The access token issuer '" + issuer + "' does not belong to tenant '" + tenant + "'.");
    }

    private boolean issuerMatches(String issuer, String tenant) {
        if (configuredIssuer != null) {
            return configuredIssuer.equals(issuer);
        }
        try {
            URI uri = URI.create(issuer);
            boolean web = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
            String path = uri.getPath();
            return web && uri.getAuthority() != null && path != null
                && (path.equalsIgnoreCase("/" + tenant + "/v2.0") || path.equalsIgnoreCase("/" + tenant + "/"));
        } catch (IllegalArgumentException expected) {
            // An issuer that is not a URI is simply not one of ours.
            return false;
        }
    }

    private static Optional<Rejection> audienceRejection(JsonNode claims, Predicate<String> acceptedAudience) {
        String audience = claims.path("aud").asText("");
        return acceptedAudience.test(audience) ? Optional.empty()
            : rejected(Reason.AUDIENCE, "The access token was issued for audience '" + audience
                + "', which this service does not accept.");
    }

    private static Optional<Rejection> invalid(String message) {
        return rejected(Reason.INVALID, message);
    }

    private static Optional<Rejection> rejected(Reason reason, String message) {
        return Optional.of(new Rejection(reason, message));
    }
}
