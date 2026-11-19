package io.floci.az.services.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The answers of the Azure AI Search data plane: JSON with the OData content type, and errors in the
 * {@code {"error":{"code","message"}}} envelope the data-plane spec declares. The spec names no error codes; the
 * codes, {@code details} and messages here follow a recording of a real service (api-version {@code 2025-09-01}).
 * Azure answers some errors with an empty {@code code}: those a request fails to parse as, and a missing index.
 */
final class SearchErrors {

    /** The content type the service answers with, as Azure sends it. */
    static final String JSON = "application/json; odata.metadata=minimal; odata.streaming=true; charset=utf-8";

    /** The code Azure answers a request it cannot read with, and a precondition or a lookup that fails. */
    static final String NO_CODE = "";
    static final String OPERATION_NOT_ALLOWED = "OperationNotAllowed";

    /** Azure's prefix of a refused request's message. */
    static final String INVALID_PREFIX = "The request is invalid. Details: ";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SearchErrors() {
    }

    /** {@code status} with {@code body} as JSON. */
    static Response.ResponseBuilder json(int status, Map<String, Object> body) {
        return Response.status(status).type(JSON).entity(write(body));
    }

    static Response error(int status, String code, String message) {
        return json(status, envelope(code, message, null)).build();
    }

    /**
     * An error with one entry in {@code details}, as Azure answers a refusal it has a code of its own for: the
     * detail repeats the message, or a shorter form of it.
     */
    static Response error(int status, String code, String message, String detailCode, String detailMessage) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", detailCode);
        detail.put("message", detailMessage);
        return json(status, envelope(code, message, List.of(detail))).build();
    }

    /** A request whose JSON does not fit the model: Azure answers these with no code. */
    static Response invalid(String detail) {
        return error(400, NO_CODE, INVALID_PREFIX + detail);
    }

    /**
     * A JSON value of the wrong type, in the words of Azure's OData reader: {@code Cannot convert the literal '3'
     * to the expected type 'Edm.String'.}
     */
    static Response cannotConvert(Object literal, String edmType) {
        return invalid("Cannot convert the literal '" + literal + "' to the expected type '" + edmType + "'.");
    }

    /** A request that reads well but that the service refuses to carry out. */
    static Response notAllowed(String detail) {
        return error(400, OPERATION_NOT_ALLOWED, INVALID_PREFIX + detail);
    }

    static Response notFound(String message) {
        return error(404, "ResourceNotFound", message);
    }

    /** A request on an index that does not exist: Azure answers it with no code. */
    static Response indexNotFound(String service, String index) {
        return error(404, NO_CODE, "No index with the name '" + index + "' was found in the service '" + service
                + "'.");
    }

    static Response preconditionFailed() {
        return error(412, NO_CODE, "The precondition given in one of the request headers evaluated to false. No"
                + " change was made to the resource from this request.");
    }

    /** {@code 403} for an {@code api-key} that is not one of the service's keys. */
    static Response forbidden() {
        return error(403, NO_CODE, "The given API key doesn't match service's internal, primary or secondary keys.");
    }

    /** {@code 401} with a Bearer challenge and no body, as Azure answers a request with no usable credential. */
    static Response unauthorized(String challenge) {
        return Response.status(401).header("WWW-Authenticate", challenge).build();
    }

    static Response methodNotAllowed() {
        return error(405, "MethodNotAllowed", "The HTTP method is not allowed on this resource.");
    }

    private static Map<String, Object> envelope(String code, String message, List<Map<String, Object>> details) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (details != null) {
            error.put("details", details);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }

    private static String write(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A search response could not be written as JSON", e);
        }
    }
}
