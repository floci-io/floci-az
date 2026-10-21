package io.floci.az.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Base64;
import java.util.Optional;

/**
 * The {@code tid} claim of a Bearer token, read as a hint of the caller's tenant. The token is decoded, never
 * verified: nothing here authenticates anyone, so the hint only decides which tenant's view a request gets, the
 * way a client that already holds a token for that tenant would see it. Verifying the signature, issuer and
 * audience is strict auth's job.
 *
 * <p>Anything that is not a three-part JWT with a JSON payload carrying a textual {@code tid} gives no hint.
 */
public final class TokenTenantHint {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BEARER = "Bearer ";

    private TokenTenantHint() {
    }

    /** The {@code tid} of the Bearer token in an {@code Authorization} header value, if it carries one. */
    public static Optional<String> of(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Optional.empty();
        }
        String[] parts = authorization.substring(BEARER.length()).trim().split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            JsonNode tid = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1])).path("tid");
            return tid.isTextual() && !tid.asText().isBlank() ? Optional.of(tid.asText()) : Optional.empty();
        } catch (IllegalArgumentException | IOException ignored) {
            // Not base64url or not JSON: an opaque token, which simply gives no hint.
            return Optional.empty();
        }
    }
}
