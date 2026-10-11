package io.floci.az.core;

import io.floci.az.core.cloud.ScopeContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;

/** Everything a routing stage needs, captured once per request. */
record RoutingContext(
    ContainerRequestContext requestContext,
    String path,
    String rawPath,
    String rawQuery,
    HttpHeaders headers,
    String host,
    boolean secure,
    String remoteAddress,
    String authority,
    ScopeContext scope
) {
    String method() {
        return requestContext.getMethod();
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
