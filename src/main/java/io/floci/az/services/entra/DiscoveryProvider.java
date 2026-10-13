package io.floci.az.services.entra;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the OpenID Connect discovery document
 * ({@code /{tenant}/v2.0/.well-known/openid-configuration}). Endpoint URLs come from the request
 * base URL, so they are reachable by the caller (host, Docker bridge, or TLS).
 *
 * <p>The capability fields ({@code grant_types_supported}, the endpoint URLs and the response
 * types and modes) are contributed by the registered {@link EntraEndpoint}s, which in turn report
 * their registered {@link GrantHandler}s. A feature that is not implemented has no bean, so it
 * cannot be advertised.
 */
@ApplicationScoped
public class DiscoveryProvider {

    /** Field order of the document: the order real Entra and the OIDC discovery spec list them in. */
    private static final List<String> FIELD_ORDER = List.of(
            "issuer",
            "authorization_endpoint",
            "token_endpoint",
            "device_authorization_endpoint",
            "userinfo_endpoint",
            "end_session_endpoint",
            "jwks_uri",
            "response_types_supported",
            "response_modes_supported",
            "subject_types_supported",
            "id_token_signing_alg_values_supported",
            "scopes_supported",
            "token_endpoint_auth_methods_supported",
            "grant_types_supported",
            "http_logout_supported",
            "frontchannel_logout_supported",
            "request_uri_parameter_supported",
            "tenant_region_scope",
            "cloud_instance_name",
            "claims_supported");

    private static final List<String> OIDC_SCOPES = List.of("openid", "profile", "email");

    private final Instance<EntraEndpoint> endpoints;
    private final IssuerFactory issuers;

    @Inject
    public DiscoveryProvider(Instance<EntraEndpoint> endpoints, IssuerFactory issuers) {
        this.endpoints = endpoints;
        this.issuers = issuers;
    }

    public Map<String, Object> document(EntraRequest request) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("issuer", issuers.discoveryIssuer(request.baseUrl(), request.tenantSegment()));
        // Kept although there is no logout route yet: msal-js throws endSessionEndpointNotSupported on
        // logout when the field is missing (msal-common src/authority/Authority.ts, endSessionEndpoint).
        fields.put("end_session_endpoint", request.tenantBase() + "/oauth2/v2.0/logout");
        fields.put("http_logout_supported", true);
        fields.put("frontchannel_logout_supported", true);
        fields.put("subject_types_supported", List.of("pairwise"));
        fields.put("request_uri_parameter_supported", false);
        fields.put("tenant_region_scope", "NA");
        fields.put("cloud_instance_name", "microsoftonline.com");
        fields.put("claims_supported", List.of(
                "sub", "iss", "aud", "exp", "iat", "nbf", "oid", "tid", "ver",
                "appid", "azp", "scp", "idtyp", "uti"));

        for (EntraEndpoint endpoint : endpoints) {
            endpoint.advertise(request, fields);
        }
        fields.put("scopes_supported", scopes(fields.get("grant_types_supported")));
        return ordered(fields);
    }

    /** {@code offline_access} asks for a refresh token, so it is listed only when that grant is served. */
    private static List<String> scopes(Object grantTypes) {
        List<String> scopes = new ArrayList<>(OIDC_SCOPES);
        if (grantTypes instanceof List<?> grants && grants.contains("refresh_token")) {
            scopes.add("offline_access");
        }
        return scopes;
    }

    private static Map<String, Object> ordered(Map<String, Object> fields) {
        Map<String, Object> remaining = new TreeMap<>(fields);
        Map<String, Object> document = new LinkedHashMap<>();
        for (String field : FIELD_ORDER) {
            if (remaining.containsKey(field)) {
                document.put(field, remaining.remove(field));
            }
        }
        document.putAll(remaining);
        return document;
    }
}
