package io.floci.az.services.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The answers of the Azure AI Search data plane: JSON with the OData content type, and errors in the
 * {@code {"error":{"code","message"}}} envelope the data-plane spec declares. The spec names no error codes, so
 * the codes here are descriptive and not verified against Azure; the SDKs act on the status code.
 */
final class SearchErrors {

    /** The content type the service answers with. */
    static final String JSON = "application/json; odata.metadata=minimal";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SearchErrors() {
    }

    /** {@code status} with {@code body} as JSON. */
    static Response.ResponseBuilder json(int status, Map<String, Object> body) {
        return Response.status(status).type(JSON).entity(write(body));
    }

    static Response error(int status, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return json(status, body).build();
    }

    /** A request the service cannot accept as it is written. */
    static Response invalid(String detail) {
        return error(400, "InvalidRequestParameter", "The request is invalid. Details: " + detail);
    }

    static Response notFound(String message) {
        return error(404, "ResourceNotFound", message);
    }

    static Response preconditionFailed() {
        return error(412, "PreconditionFailed", "The precondition given in one of the request headers evaluated to"
                + " false. No change was made to the resource from this request.");
    }

    static Response forbidden() {
        return error(403, "Forbidden", "The api-key is not valid for this operation on this search service.");
    }

    static Response methodNotAllowed() {
        return error(405, "MethodNotAllowed", "The HTTP method is not allowed on this resource.");
    }

    private static String write(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A search response could not be written as JSON", e);
        }
    }
}
