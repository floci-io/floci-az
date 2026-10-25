package io.floci.az.core.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The claims of a request's bearer token, read without verifying the signature: enough to know who is
 * calling (object id, application id, user name) and whether the caller is a user, an application or a
 * managed identity.
 * Every ARM service stamps the caller into {@code systemData}, and the token is the only place the
 * caller's identity travels, so the claims are read even when token validation is off.
 *
 * @param oid     the caller's object id, or null
 * @param appId   the application id ({@code appid}, else {@code azp}), or null
 * @param upn     the user principal name ({@code upn}, else {@code unique_name} or {@code preferred_username}),
 *                or null for an application
 * @param idtyp   {@code app}, {@code user} or null when the token does not say and no claim implies it
 * @param managedIdentity true when the token names a managed identity resource ({@code xms_mirid} or {@code xms_az_rid})
 * @param claims  every claim of the token as a string, for the Activity Log; empty when not read
 * @param values  every claim of the token with its JSON type kept (arrays stay lists), for policy
 *                expressions that read {@code requestContext().identity}; empty when not read
 */
public record BearerClaims(String oid, String appId, String upn, String idtyp, boolean managedIdentity,
                           Map<String, String> claims, Map<String, Object> values) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final BearerClaims NONE = new BearerClaims(null, null, null, null, false, Map.of(), Map.of());

    /** Claims without the full maps, for callers that only need the identity. */
    public BearerClaims(String oid, String appId, String upn, String idtyp, boolean managedIdentity) {
        this(oid, appId, upn, idtyp, managedIdentity, Map.of(), Map.of());
    }

    /** Claims with their string forms only. */
    public BearerClaims(String oid, String appId, String upn, String idtyp, boolean managedIdentity,
                        Map<String, String> claims) {
        this(oid, appId, upn, idtyp, managedIdentity, claims, Map.of());
    }

    /** The claims of the request's bearer token, or {@link #NONE} when it carries none that can be read. */
    public static BearerClaims of(AzureRequest request) {
        if (request == null || request.headers() == null) {
            return NONE;
        }
        return parse(request.headers().getHeaderString("Authorization"));
    }

    /** The claims of an {@code Authorization} header value, or {@link #NONE}. */
    public static BearerClaims parse(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return NONE;
        }
        String[] parts = authorization.substring(7).trim().split("\\.");
        if (parts.length < 2) {
            return NONE;
        }
        try {
            Map<?, ?> claims = MAPPER.readValue(Base64.getUrlDecoder().decode(padded(parts[1])), Map.class);
            if (claims == null) {
                return NONE; // a payload of JSON null
            }
            Map<String, String> all = new LinkedHashMap<>();
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : claims.entrySet()) {
                if (entry.getValue() != null) {
                    all.put(String.valueOf(entry.getKey()), entry.getValue() instanceof String text ? text
                        : entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof java.util.List<?>
                            ? MAPPER.writeValueAsString(entry.getValue()) : String.valueOf(entry.getValue()));
                    typed.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            String oid = string(claims, "oid");
            String appId = string(claims, "appid");
            if (appId == null) {
                appId = string(claims, "azp");
            }
            String upn = string(claims, "upn");
            if (upn == null) {
                upn = string(claims, "unique_name");
            }
            if (upn == null) {
                upn = string(claims, "preferred_username");
            }
            boolean managedIdentity = string(claims, "xms_mirid") != null || string(claims, "xms_az_rid") != null;
            String idtyp = string(claims, "idtyp");
            if (idtyp == null && (upn != null || appId != null || oid != null)) {
                // A delegated token carries the user's scopes (scp); an app-only token carries roles instead.
                idtyp = upn != null || string(claims, "scp") != null ? "user" : "app";
            }
            return new BearerClaims(oid, appId, upn, idtyp, managedIdentity, Map.copyOf(all), Map.copyOf(typed));
        } catch (IOException | IllegalArgumentException e) {
            return NONE;
        }
    }

    public boolean present() {
        return oid != null || appId != null || upn != null;
    }

    /**
     * The claims as the Activity Log reports them: every claim as a string, plus the long-form names
     * Azure adds for the object id, tenant, user name and scope when the short ones are present.
     */
    public Map<String, String> activityLogClaims() {
        Map<String, String> out = new LinkedHashMap<>(claims);
        putIfPresent(out, "appid", appId);
        putIfPresent(out, "http://schemas.microsoft.com/identity/claims/objectidentifier", claims.get("oid"));
        putIfPresent(out, "http://schemas.microsoft.com/identity/claims/tenantid", claims.get("tid"));
        putIfPresent(out, "http://schemas.microsoft.com/identity/claims/scope", claims.get("scp"));
        putIfPresent(out, "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn", upn);
        putIfPresent(out, "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name", upn);
        return out;
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !target.containsKey(key)) {
            target.put(key, value);
        }
    }

    /**
     * The principal ARM writes as {@code createdBy} / {@code lastModifiedBy}: a user's principal name, or an
     * application's or managed identity's client id; the object id only when the token lacks those.
     */
    public String principal() {
        if (isUser()) {
            return upn != null ? upn : oid;
        }
        if (appId != null) {
            return appId;
        }
        return oid != null ? oid : upn;
    }

    /** ARM's {@code createdByType} / {@code lastModifiedByType}: {@code User}, {@code ManagedIdentity} or {@code Application}. */
    public String principalType() {
        if (isUser()) {
            return "User";
        }
        return managedIdentity ? "ManagedIdentity" : "Application";
    }

    /**
     * The principal's object id, as Azure Policy writes {@code metadata.createdBy} and {@code updatedBy}; the ARM
     * principal when the token has none.
     */
    public String objectId() {
        return oid != null ? oid : principal();
    }

    private boolean isUser() {
        return "user".equalsIgnoreCase(idtyp);
    }

    private static String string(Map<?, ?> claims, String key) {
        Object value = claims.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static String padded(String base64url) {
        int remainder = base64url.length() % 4;
        return remainder == 0 ? base64url : base64url + "=".repeat(4 - remainder);
    }
}
