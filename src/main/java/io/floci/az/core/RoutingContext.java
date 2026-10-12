package io.floci.az.core;

import io.floci.az.core.cloud.ScopeContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;

import java.io.InputStream;

/**
 * Everything a routing stage needs, captured once per request. It carries no JAX-RS request object, so a
 * request floci-az makes to itself ({@link ArmRouter#dispatchInternal}) runs the same stages a client's does.
 *
 * @param method          the HTTP method
 * @param path            the decoded path, without a leading slash
 * @param rawPath         the path as the client encoded it, without a leading slash
 * @param rawQuery        the query as the client sent it, without the {@code ?}; null when absent
 * @param headers         the request headers, read by handlers
 * @param mutableHeaders  the same headers as a mutable map, for the one header routing itself stamps
 *                        ({@code x-floci-account-suffix})
 * @param queryParameters the decoded query parameters, repeated ones kept
 * @param body            the request body
 */
record RoutingContext(
    String method,
    String path,
    String rawPath,
    String rawQuery,
    HttpHeaders headers,
    MultivaluedMap<String, String> mutableHeaders,
    MultivaluedMap<String, String> queryParameters,
    InputStream body,
    String host,
    boolean secure,
    String remoteAddress,
    String authority,
    ScopeContext scope
) {

    /** The context of a request that arrived over the transport. */
    static RoutingContext of(ContainerRequestContext requestContext, String path, String rawPath, String rawQuery,
                             HttpHeaders headers, String host, String remoteAddress, String authority,
                             ScopeContext scope) {
        return new RoutingContext(requestContext.getMethod(), path, rawPath, rawQuery, headers,
            requestContext.getHeaders(), requestContext.getUriInfo().getQueryParameters(),
            requestContext.getEntityStream(), host, requestContext.getSecurityContext().isSecure(), remoteAddress,
            authority, scope);
    }

    /** First path segment ({@code ""} for a root request). */
    String firstSegment() {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    /** Everything after the first path segment, without the separating slash. */
    String resourcePath() {
        int slash = path.indexOf('/');
        return slash < 0 ? "" : path.substring(slash + 1);
    }
}
