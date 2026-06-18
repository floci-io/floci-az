package io.floci.az.services.entra;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the OpenID Connect discovery document
 * ({@code /{tenant}/v2.0/.well-known/openid-configuration}). All endpoints are derived from the
 * incoming request base URL so they are reachable by the caller (host, Docker bridge, or TLS).
 */
@ApplicationScoped
public class DiscoveryProvider {

    /**
     * @param baseUrl  request base URL with no trailing slash (e.g. {@code http://localhost:4577})
     * @param tenantId tenant segment from the request path
     */
    public Map<String, Object> document(String baseUrl, String tenantId) {
        String tenantBase = baseUrl + "/" + tenantId;

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("issuer", tenantBase + "/v2.0");
        doc.put("authorization_endpoint", tenantBase + "/oauth2/v2.0/authorize");
        doc.put("token_endpoint", tenantBase + "/oauth2/v2.0/token");
        doc.put("device_authorization_endpoint", tenantBase + "/oauth2/v2.0/devicecode");
        doc.put("userinfo_endpoint", baseUrl + "/oidc/userinfo");
        doc.put("end_session_endpoint", tenantBase + "/oauth2/v2.0/logout");
        doc.put("jwks_uri", tenantBase + "/discovery/v2.0/keys");
        doc.put("response_types_supported",
            List.of("code", "id_token", "code id_token", "id_token token"));
        doc.put("response_modes_supported", List.of("query", "fragment", "form_post"));
        doc.put("subject_types_supported", List.of("pairwise"));
        doc.put("id_token_signing_alg_values_supported", List.of("RS256"));
        doc.put("scopes_supported", List.of("openid", "profile", "email", "offline_access"));
        doc.put("token_endpoint_auth_methods_supported",
            List.of("client_secret_post", "client_secret_basic", "private_key_jwt"));
        doc.put("grant_types_supported", List.of(
            "authorization_code", "refresh_token", "client_credentials", "password",
            "urn:ietf:params:oauth:grant-type:device_code"));
        doc.put("http_logout_supported", true);
        doc.put("frontchannel_logout_supported", true);
        doc.put("request_uri_parameter_supported", false);
        doc.put("tenant_region_scope", "NA");
        doc.put("cloud_instance_name", "microsoftonline.com");
        doc.put("claims_supported", List.of(
            "sub", "iss", "aud", "exp", "iat", "nbf", "oid", "tid", "ver",
            "appid", "azp", "scp", "idtyp", "uti"));
        return doc;
    }
}
