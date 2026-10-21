package io.floci.az.services.entra;

import java.util.Arrays;
import java.util.Set;

/** Reads the audience and the {@code scp} claim out of a {@code scope} (or v1 {@code resource}) value. */
final class Scopes {

    /** OIDC scopes that ask for an id token or a refresh token, never for resource access. */
    private static final Set<String> OIDC_RESERVED = Set.of("openid", "profile", "email", "offline_access");

    private Scopes() {
    }

    /** v2.0 client_credentials scope is {@code {resource}/.default}; the audience is the resource. */
    static String clientCredentialsAudience(String scope, String baseUrl) {
        String first = firstScope(scope, baseUrl);
        if (first.endsWith("/.default")) {
            return first.substring(0, first.length() - "/.default".length());
        }
        return first;
    }

    /** Delegated access tokens retain OIDC consent so UserInfo can enforce it. */
    static String delegatedScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        return String.join(" ", Arrays.stream(scope.trim().split("\\s+"))
                .filter(value -> !"offline_access".equals(value)).toList());
    }

    /** The first requested scope, or the base URL when none was sent. */
    static String firstScope(String scope, String baseUrl) {
        if (scope == null || scope.isBlank()) {
            return baseUrl;
        }
        return scope.split("\\s+")[0];
    }

    /** The {@code scp} claim: the requested scopes minus the OIDC reserved ones, or null when none remain. */
    static String normalize(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : scope.split("\\s+")) {
            if (OIDC_RESERVED.contains(s)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
