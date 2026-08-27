package io.floci.az.core;

import jakarta.ws.rs.core.HttpHeaders;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

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
    String remoteAddress     // transport peer address; never derived from forwarded headers
) {

    public AzureRequest(String method, String accountName, String serviceType, String resourcePath,
                        HttpHeaders headers, InputStream bodyStream, Map<String, String> queryParams,
                        Map<String, List<String>> queryParamsMulti, AuthContext authContext,
                        boolean secure) {
        this(method, accountName, serviceType, resourcePath, headers, bodyStream,
             queryParams, queryParamsMulti, authContext, secure, null, null);
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
             queryParams, Map.of(), authContext, secure, null, null);
    }

    /**
     * Keeps the host captured before blocking dispatch available without reading request-scoped headers.
     */
    public AzureRequest(String method, String accountName, String serviceType, String resourcePath,
                        HttpHeaders headers, InputStream bodyStream, Map<String, String> queryParams,
                        Map<String, List<String>> queryParamsMulti, AuthContext authContext, boolean secure,
                        String host) {
        this(method, accountName, serviceType, resourcePath, headers, bodyStream,
             queryParams, queryParamsMulti, authContext, secure, host, null);
    }

    public AzureRequest(String method, String accountName, String serviceType, String resourcePath,
                        HttpHeaders headers, InputStream bodyStream, Map<String, String> queryParams,
                        AuthContext authContext, boolean secure, String remoteAddress) {
        this(method, accountName, serviceType, resourcePath, headers, bodyStream,
             queryParams, Map.of(), authContext, secure, null, remoteAddress);
    }

    /**
     * Returns a copy of this request carrying the resolved {@link AuthContext}. The routing filter
     * builds a request with a {@code null} auth context, feeds it to the auth pipeline, then rebuilds
     * it with the result; this keeps that rebuild from restating all components positionally.
     */
    public AzureRequest withAuthContext(AuthContext resolved) {
        return new AzureRequest(method, accountName, serviceType, resourcePath, headers, bodyStream,
             queryParams, queryParamsMulti, resolved, secure, host, remoteAddress);
    }
}
