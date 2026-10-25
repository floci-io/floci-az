package io.floci.az.core;

import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.cloud.ScopeContext;
import jakarta.ws.rs.core.HttpHeaders;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record AzureRequest(
    String method,
    String accountName,
    String serviceType,      // "blob", "queue", "table": resolved at dispatch
    String resourcePath,     // everything after /{accountName}/
    HttpHeaders headers,
    InputStream bodyStream,
    Map<String, String> queryParams,
    Map<String, List<String>> queryParamsMulti, // repeated query params preserved (e.g. App Config `tags`)
    AuthContext authContext,
    boolean secure,          // true when the request arrived over HTTPS
    String host,             // host captured before async/blocking dispatch; may be null for direct/internal requests
    String remoteAddress,    // transport peer address; never derived from forwarded headers
    String rawPath,          // original encoded request path, without a leading slash
    String rawQuery,         // original encoded query string, without the leading "?"; null when absent
    String authority,        // host[:port] the client addressed (HTTP/1.1 Host, HTTP/2 :authority); null when unknown
    ScopeContext scope,      // cloud, tenant, subscription and resource group, resolved once by the routing filter; may be null
    ArmCall armCall          // set while the request is inside the ARM request gate; null otherwise
) {

    /**
     * A request built from a transport: it carries the authority the client addressed, but no resolved
     * scope yet and it is outside the ARM request gate.
     */
    public AzureRequest(String method, String accountName, String serviceType, String resourcePath,
                        HttpHeaders headers, InputStream bodyStream, Map<String, String> queryParams,
                        Map<String, List<String>> queryParamsMulti, AuthContext authContext, boolean secure,
                        String host, String remoteAddress, String rawPath, String rawQuery, String authority) {
        this(method, accountName, serviceType, resourcePath, headers, bodyStream, queryParams, queryParamsMulti,
             authContext, secure, host, remoteAddress, rawPath, rawQuery, authority, null, null);
    }

    /**
     * Pre-authority form, kept for callers that build a request without a transport (tests, internal
     * dispatch). {@link RequestUrls} falls back to the {@code Host} header for these. No resolved scope,
     * and outside the ARM request gate.
     */
    public AzureRequest(String method, String accountName, String serviceType, String resourcePath,
                        HttpHeaders headers, InputStream bodyStream, Map<String, String> queryParams,
                        Map<String, List<String>> queryParamsMulti, AuthContext authContext, boolean secure,
                        String host, String remoteAddress, String rawPath, String rawQuery) {
        this(method, accountName, serviceType, resourcePath, headers, bodyStream, queryParams, queryParamsMulti,
             authContext, secure, host, remoteAddress, rawPath, rawQuery, null, null, null);
    }

    public AzureRequest(String method, String accountName, String serviceType, String resourcePath,
                        HttpHeaders headers, InputStream bodyStream, Map<String, String> queryParams,
                        Map<String, List<String>> queryParamsMulti, AuthContext authContext,
                        boolean secure) {
        this(method, accountName, serviceType, resourcePath, headers, bodyStream,
             queryParams, queryParamsMulti, authContext, secure, null, null, resourcePath, null);
    }

    /**
     * Backwards-compatible constructor for the majority of call sites that only ever read
     * single-valued query params. Repeated params collapse to {@code queryParamsMulti = {}};
     * use the canonical constructor when a handler needs multi-valued parameters.
     */
    public AzureRequest(String method, String accountName, String serviceType, String resourcePath,
                        HttpHeaders headers, InputStream bodyStream, Map<String, String> queryParams,
                        AuthContext authContext, boolean secure) {
        this(method, accountName, serviceType, resourcePath, headers, bodyStream,
             queryParams, Map.of(), authContext, secure, null, null, resourcePath, null);
    }

    /**
     * Keeps the host captured before blocking dispatch available without reading request-scoped headers.
     */
    public AzureRequest(String method, String accountName, String serviceType, String resourcePath,
                        HttpHeaders headers, InputStream bodyStream, Map<String, String> queryParams,
                        Map<String, List<String>> queryParamsMulti, AuthContext authContext, boolean secure,
                        String host) {
        this(method, accountName, serviceType, resourcePath, headers, bodyStream,
             queryParams, queryParamsMulti, authContext, secure, host, null, resourcePath, null);
    }

    public AzureRequest(String method, String accountName, String serviceType, String resourcePath,
                        HttpHeaders headers, InputStream bodyStream, Map<String, String> queryParams,
                        AuthContext authContext, boolean secure, String remoteAddress) {
        this(method, accountName, serviceType, resourcePath, headers, bodyStream,
             queryParams, Map.of(), authContext, secure, null, remoteAddress, resourcePath, null);
    }

    /**
     * Returns a copy of this request carrying the resolved {@link AuthContext}. The routing filter
     * builds a request with a {@code null} auth context, feeds it to the auth pipeline, then rebuilds
     * it with the result; this keeps that rebuild from restating all components positionally.
     */
    public AzureRequest withAuthContext(AuthContext resolved) {
        return new AzureRequest(method, accountName, serviceType, resourcePath, headers, bodyStream,
             queryParams, queryParamsMulti, resolved, secure, host, remoteAddress, rawPath, rawQuery, authority, scope,
             armCall);
    }

    /**
     * The resource group the request's ARM path names, as the routing filter resolved it; empty for a path
     * that names none (a subscription-wide listing) and for a request built without a scope.
     */
    public Optional<String> resourceGroup() {
        return scope == null ? Optional.empty() : Optional.ofNullable(scope.resourceGroup());
    }

    /** Returns a copy of this request carrying the scope the routing filter resolved. */
    public AzureRequest withScope(ScopeContext resolved) {
        return new AzureRequest(method, accountName, serviceType, resourcePath, headers, bodyStream,
             queryParams, queryParamsMulti, authContext, secure, host, remoteAddress, rawPath, rawQuery, authority,
             resolved, armCall);
    }

    /**
     * A copy of this request that outlives it, for work that goes on after the response is sent (a template
     * deployment running in the background): the request-scoped headers are replaced by a snapshot of the ones an
     * internal request forwards ({@code Authorization}, {@code X-Forwarded-Proto}, {@code Host}), the authority is
     * resolved now, and the body is dropped, from the request and from its {@link ArmCall}, which keeps no
     * reference to the original request.
     */
    public AzureRequest detached() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        if (headers != null) {
            for (String name : List.of(HttpHeaders.AUTHORIZATION, "X-Forwarded-Proto", HttpHeaders.HOST)) {
                String value = headers.getHeaderString(name);
                if (value != null) {
                    snapshot.put(name, value);
                }
            }
        }
        return new AzureRequest(method, accountName, serviceType, resourcePath, new SyntheticHttpHeaders(snapshot),
             InputStream.nullInputStream(), queryParams, queryParamsMulti, authContext, secure, host, remoteAddress,
             rawPath, rawQuery, RequestUrls.resolveAuthority(this), scope, armCall == null ? null
                 : new ArmCall(armCall.scope(), armCall.id(), armCall.type(), armCall.method(),
                     armCall.normalizedLocation()));
    }

    /** Returns a copy of this request marked as inside the ARM request gate with {@code call}. */
    public AzureRequest withArm(ArmCall call) {
        return withArm(call, bodyStream);
    }

    /** Returns a copy of this request inside the gate with {@code call}, reading its body from {@code body}. */
    public AzureRequest withArm(ArmCall call, InputStream body) {
        return new AzureRequest(method, accountName, serviceType, resourcePath, headers, body,
             queryParams, queryParamsMulti, authContext, secure, host, remoteAddress, rawPath, rawQuery, authority,
             scope, call);
    }
}
