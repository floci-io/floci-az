package io.floci.az.core;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@RegisterForReflection
@JacksonXmlRootElement(localName = "Error")
public record AzureErrorResponse(
    String Code,
    String Message
) {
    public Response toXmlResponse(int httpStatus) {
        return Response.status(httpStatus)
            .type(MediaType.APPLICATION_XML)
            .header("x-ms-error-code", Code)
            .entity(XmlUtils.toXml(this))
            .build();
    }

    public Response toJsonResponse(int httpStatus) {
        return Response.status(httpStatus)
            .type(MediaType.APPLICATION_JSON)
            .header("x-ms-error-code", Code)
            .entity(this)
            .build();
    }

    // The Table service wraps errors in the OData envelope; SDKs (notably .NET
    // Azure.Data.Tables) parse the body, not just the x-ms-error-code header.
    public Response toODataResponse(int httpStatus) {
        return Response.status(httpStatus)
            .type("application/json;odata=minimalmetadata")
            .header("x-ms-error-code", Code)
            .entity(Map.of("odata.error", Map.of(
                "code", Code,
                "message", Map.of("lang", "en-US", "value", Message))))
            .build();
    }
}
