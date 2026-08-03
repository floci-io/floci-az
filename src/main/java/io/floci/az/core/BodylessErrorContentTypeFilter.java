package io.floci.az.core;

import org.jboss.resteasy.reactive.server.ServerResponseFilter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.HttpHeaders;

/**
 * Strips {@code Content-Type} from error responses that cannot carry a body.
 *
 * <p>A HEAD response never includes content (RFC 9110 §9.3.2), so JAX-RS discards the entity while
 * keeping the content type set by {@link AzureErrorResponse}. That leaves
 * {@code content-type: application/xml} on a zero-byte response - a combination the Azure SDK for C++
 * cannot survive: {@code StorageException::CreateFromResponse} parses the body whenever the content
 * type contains {@code "xml"} (or {@code "json"}) without checking that the buffer is non-empty, and
 * the resulting {@code std::runtime_error} escapes the {@code RequestFailedException} constructor
 * before any catch clause exists, calling {@code terminate()}.
 *
 * <p>Azurite gates both the content type and the body write on the request method for this reason,
 * leaving the status code and the remaining headers untouched. This filter mirrors that behavior.
 * {@code x-ms-error-code} is deliberately preserved: it is the SDK's documented fallback for
 * recovering the error code once body parsing is skipped.
 *
 * <p>Restricted to error statuses: {@code Get Blob Properties} documents {@code Content-Type} among
 * its 200 response headers, so a successful HEAD must keep it.
 */
public class BodylessErrorContentTypeFilter {

    @ServerResponseFilter
    public void stripContentTypeOnBodylessErrors(ContainerRequestContext request,
                                                 ContainerResponseContext response) {
        if (response.getStatus() >= 400 && "HEAD".equalsIgnoreCase(request.getMethod())) {
            response.getHeaders().remove(HttpHeaders.CONTENT_TYPE);
        }
    }
}
