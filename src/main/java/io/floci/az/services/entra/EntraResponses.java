package io.floci.az.services.entra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Renders the token service's JSON bodies: documents, token responses and {@link AadstsError}s. */
@ApplicationScoped
public class EntraResponses {

    private static final Logger LOG = Logger.getLogger(EntraResponses.class);

    private static final DateTimeFormatter AAD_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final IssuerFactory issuers;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public EntraResponses(IssuerFactory issuers) {
        this.issuers = issuers;
    }

    public Response json(Object body) {
        try {
            return Response.ok(mapper.writeValueAsString(body)).type(MediaType.APPLICATION_JSON).build();
        } catch (JsonProcessingException e) {
            LOG.errorv(e, "Entra: could not serialise a response body");
            return error(AadstsError.SERIALISATION_FAILED);
        }
    }

    /** A token response; {@code scope} and {@code idToken} are included only when present. */
    public Response token(String accessToken, String idToken, long expiresIn, String scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token_type", "Bearer");
        body.put("expires_in", expiresIn);
        body.put("ext_expires_in", expiresIn);
        if (scope != null && !scope.isBlank()) {
            body.put("scope", scope);
        }
        body.put("access_token", accessToken);
        if (idToken != null) {
            body.put("id_token", idToken);
        }
        return json(body);
    }

    /**
     * The Entra error envelope. Azure embeds the {@code AADSTS} code plus trace, correlation and
     * timestamp inside {@code error_description} and repeats them as discrete fields; MSAL and
     * azure-identity read {@code error_codes} for their retry decisions.
     */
    public Response error(AadstsError error, Object... args) {
        String traceId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        String timestamp = AAD_TIMESTAMP.format(Instant.now());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error.oauthError());
        body.put("error_description", "AADSTS" + error.code() + ": " + error.message(args)
                + "\r\nTrace ID: " + traceId
                + "\r\nCorrelation ID: " + correlationId
                + "\r\nTimestamp: " + timestamp);
        body.put("error_codes", List.of(error.code()));
        body.put("timestamp", timestamp);
        body.put("trace_id", traceId);
        body.put("correlation_id", correlationId);
        body.put("error_uri", issuers.errorUri(error.code()));
        try {
            return Response.status(error.httpStatus()).entity(mapper.writeValueAsString(body))
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (JsonProcessingException e) {
            LOG.errorv(e, "Entra: could not serialise the {0} error body", error);
            return Response.status(error.httpStatus()).build();
        }
    }
}
