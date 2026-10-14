package io.floci.az.services.entra;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * How a token request authenticates its client: {@code client_secret_post} form values, or
 * {@code client_secret_basic} (RFC 6749 section 2.3.1), or a {@code client_assertion}.
 *
 * @param clientId     the {@code client_id}, from the form or else from HTTP Basic; null when neither sent one
 * @param clientSecret the secret, from the form or else from HTTP Basic; null when none was sent
 * @param assertion    whether the request carries a {@code client_assertion}
 */
public record ClientCredentials(String clientId, String clientSecret, boolean assertion) {

    private static final String BASIC = "basic ";

    /** Reads the client credentials of a token request; form values win over the Basic header. */
    public static ClientCredentials from(Map<String, String> form, String authorizationHeader) {
        String basicId = null;
        String basicSecret = null;
        if (authorizationHeader != null && authorizationHeader.toLowerCase(Locale.ROOT).startsWith(BASIC)) {
            String decoded = decodeBasic(authorizationHeader.substring(BASIC.length()).trim());
            int colon = decoded == null ? -1 : decoded.indexOf(':');
            if (colon >= 0) {
                basicId = urlDecode(decoded.substring(0, colon));
                basicSecret = urlDecode(decoded.substring(colon + 1));
            }
        }
        String clientId = firstNonBlank(form.get("client_id"), basicId);
        String clientSecret = firstNonBlank(form.get("client_secret"), basicSecret);
        String assertion = form.get("client_assertion");
        return new ClientCredentials(clientId, clientSecret, assertion != null && !assertion.isBlank());
    }

    private static String decodeBasic(String encoded) {
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return value;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }
}
