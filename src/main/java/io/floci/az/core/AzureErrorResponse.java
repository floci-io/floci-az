package io.floci.az.core;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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
}
