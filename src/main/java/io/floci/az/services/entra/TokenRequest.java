package io.floci.az.services.entra;

import java.util.Map;

/**
 * A token endpoint request after the parts every grant shares have been resolved.
 *
 * @param form            the decoded form body
 * @param tenantId        the tenant the token is issued in
 * @param baseUrl         the request base URL, no trailing slash
 * @param v2              whether the v2.0 endpoint was called (controls token shape and audience)
 * @param clientId        the {@code client_id} form value, or null
 * @param appId           the application id the token is issued to
 * @param clientObjectId  the object id of the client's service principal
 * @param issuer          the {@code iss} claim
 * @param scope           {@code scope}, or the v1 {@code resource} when no scope was sent
 * @param lifetimeSeconds the access token lifetime
 */
public record TokenRequest(Map<String, String> form, String tenantId, String baseUrl, boolean v2,
                           String clientId, String appId, String clientObjectId, String issuer,
                           String scope, long lifetimeSeconds) {

    /** The {@code ver} claim: {@code "2.0"} or {@code "1.0"}. */
    public String tokenVersion() {
        return v2 ? "2.0" : "1.0";
    }
}
