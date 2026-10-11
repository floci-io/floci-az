package io.floci.az.services.blob;

import io.floci.az.core.XmlBuilder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A rejected blob index tag request: an invalid tag set, an unparsable {@code where} filter or
 * {@code x-ms-if-tags} condition. Carries the Storage error code and the extra detail elements
 * (for example {@code QueryParameterName}) that the Storage XML error envelope appends after
 * {@code Message}.
 */
final class BlobTagException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, String> details;

    BlobTagException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    BlobTagException(int status, String code, String message, Map<String, String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = new LinkedHashMap<>(details);
    }

    static BlobTagException invalidQuery(String where, String message) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("QueryParameterName", "where");
        details.put("QueryParameterValue", where);
        return new BlobTagException(400, "InvalidQueryParameterValue", message, details);
    }

    static BlobTagException invalidHeader(String headerName, String headerValue) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("HeaderName", headerName);
        details.put("HeaderValue", headerValue);
        return new BlobTagException(400, "InvalidHeaderValue",
                "The value for one of the HTTP headers is not in the correct format.", details);
    }

    String code() {
        return code;
    }

    int status() {
        return status;
    }

    Response toResponse() {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                .start("Error")
                .elem("Code", code)
                .elem("Message", getMessage());
        details.forEach(xml::elem);
        return Response.status(status)
                .type(MediaType.APPLICATION_XML)
                .header("x-ms-error-code", code)
                .entity(xml.end("Error").build())
                .build();
    }
}
