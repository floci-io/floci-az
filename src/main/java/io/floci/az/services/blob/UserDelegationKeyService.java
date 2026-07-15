package io.floci.az.services.blob;

import io.floci.az.core.AzureErrorResponse;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.XmlBuilder;
import io.floci.az.core.XmlParser;
import io.floci.az.core.auth.UserDelegationKeyMaterial;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class UserDelegationKeyService {

    private static final Logger LOG = Logger.getLogger(UserDelegationKeyService.class);
    private static final Duration MAX_KEY_DURATION = Duration.ofDays(7);
    private static final String DEFAULT_SIGNED_VERSION = "2024-11-04";

    public Response create(AzureRequest request) {
        String body;
        try {
            body = new String(request.bodyStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.errorf(e, "Failed reading user delegation key request body for account=%s", request.accountName());
            return Response.serverError().build();
        }

        String startText = trimToNull(XmlParser.extractFirst(body, "Start", null));
        String expiryText = trimToNull(XmlParser.extractFirst(body, "Expiry", null));
        if (expiryText == null) {
            return invalidXml("The XML specified is not syntactically valid.");
        }

        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        if (startText != null) {
            start = parseTimestamp(startText);
            if (start == null) {
                return invalidXml("The XML specified is not syntactically valid.");
            }
        }

        OffsetDateTime expiry = parseTimestamp(expiryText);
        if (expiry == null) {
            return invalidXml("The XML specified is not syntactically valid.");
        }
        if (!expiry.toInstant().isAfter(start.toInstant())) {
            return outOfRange("The value for one of the XML nodes is not in the correct range.");
        }
        if (Duration.between(start.toInstant(), expiry.toInstant()).compareTo(MAX_KEY_DURATION) > 0) {
            return outOfRange("User delegation key expiry must be within seven days of the start time.");
        }

        String signedVersion = trimToNull(request.headers().getHeaderString("x-ms-version"));
        if (signedVersion == null) {
            signedVersion = DEFAULT_SIGNED_VERSION;
        }

        String xml = new XmlBuilder()
                .start("UserDelegationKey")
                .elem("SignedOid", UserDelegationKeyMaterial.SIGNED_OBJECT_ID)
                .elem("SignedTid", UserDelegationKeyMaterial.SIGNED_TENANT_ID)
                .elem("SignedStart", format(start))
                .elem("SignedExpiry", format(expiry))
                .elem("SignedService", "b")
                .elem("SignedVersion", signedVersion)
                .elem("Value", UserDelegationKeyMaterial.signingKeyForAccount(request.accountName()))
                .end("UserDelegationKey")
                .build();
        return Response.ok(xml, "application/xml").build();
    }

    private static OffsetDateTime parseTimestamp(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(value).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static String format(OffsetDateTime value) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                value.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Response invalidXml(String message) {
        return new AzureErrorResponse("InvalidXmlDocument", message)
                .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
    }

    private static Response outOfRange(String message) {
        return new AzureErrorResponse("OutOfRangeInput", message)
                .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
    }
}
