package io.floci.az.core.auth;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The 403 {@code AuthenticationFailed} a storage data plane answers a request whose credentials it cannot verify, in
 * the shape Azure answers it: the message ends with the request id and the time, and the XML body carries an
 * {@code AuthenticationErrorDetail} that says what failed. The response carries the {@code x-ms-request-id} the
 * message names.
 */
public final class AuthenticationFailed {

    private static final String CODE = "AuthenticationFailed";
    private static final String MESSAGE = "Server failed to authenticate the request. Make sure the value of "
            + "Authorization header is formed correctly including the signature.";
    /** Azure's {@code Time:} line: UTC with seven fractional digits. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'");

    private AuthenticationFailed() {
    }

    /** The Blob and Queue services' XML error. */
    public static Response xml(Optional<String> detail) {
        String requestId = UUID.randomUUID().toString();
        StringBuilder body = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><Error><Code>")
                .append(CODE).append("</Code><Message>").append(text(message(requestId)))
                .append("</Message>");
        detail.ifPresent(value -> body.append("<AuthenticationErrorDetail>").append(text(value))
                .append("</AuthenticationErrorDetail>"));
        body.append("</Error>");
        return response(requestId).type(MediaType.APPLICATION_XML).entity(body.toString()).build();
    }

    /** The Table service's OData error, which has no field for the detail. */
    public static Response odata() {
        String requestId = UUID.randomUUID().toString();
        return response(requestId).type("application/json;odata=minimalmetadata")
                .entity(Map.of("odata.error", Map.of(
                        "code", CODE,
                        "message", Map.of("lang", "en-US", "value", message(requestId)))))
                .build();
    }

    /** XML text as the service writes it: quotes, which the detail's string to sign holds, are left as they are. */
    private static String text(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Response.ResponseBuilder response(String requestId) {
        return Response.status(Response.Status.FORBIDDEN)
                .header("x-ms-error-code", CODE)
                .header("x-ms-request-id", requestId);
    }

    private static String message(String requestId) {
        return MESSAGE + "\nRequestId:" + requestId + "\nTime:" + TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
    }
}
