package io.floci.az.services.blob;

import io.floci.az.core.XmlBuilder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A Blob Batch request and its Multipart response: {@code POST /{container}?restype=container&comp=batch}
 * over one container, or {@code POST /?restype=service&comp=batch} over the account.
 *
 * <p>The request body is {@code multipart/mixed}: each part carries {@code Content-Type: application/http}
 * and a {@code Content-ID}, then an embedded HTTP request addressed at a blob, followed by that request's
 * headers: {@code DELETE /{account}/{container}/{blob}?{query} HTTP/1.1} on the path-addressed endpoint, and
 * {@code DELETE /{container}/{blob}?{query} HTTP/1.1} on the host-addressed one. The response is one
 * {@code 202 Accepted} whose body answers every sub-request in turn: each part echoes its
 * {@code Content-ID} and carries the sub-response's own status line, headers and error body. Azure clients
 * read the per-part status and {@code x-ms-error-code}, not the batch's own 202, so a batch of deletes
 * reports each blob's outcome, including a missing blob's {@code 404 BlobNotFound}, on its own part.
 *
 * <p>Only Blob Delete is implemented. {@code Set Blob Tier} and {@code Set Blob Expiry} are the other
 * sub-request operations Azure defines, and neither operation exists in this emulator, so a batch of them
 * carries a single {@code 501 NotImplemented} error part rather than per-part successes that change nothing.
 */
final class BlobBatch {

    /** Azure's per-batch limit; a larger batch is rejected whole, as it is in Azure. */
    private static final int MAX_SUB_REQUESTS = 256;

    private static final String LINE_ENDING = "\r\n";
    private static final String LINE_PATTERN = "\\r?\\n";
    private static final String BOUNDARY_PARAMETER = "boundary=";
    private static final String CONTENT_ID = "content-id";

    private BlobBatch() {
    }

    /**
     * One sub-request: the Blob Delete it asks for, plus the request headers and query of the operation,
     * conditions ({@code If-Match}, {@code x-ms-if-tags}), {@code x-ms-delete-snapshots} and the blob's SAS
     * all travel there, and the cancel semantics of the delete are the standalone operation's.
     */
    record SubRequest(int contentId, String container, String blob, Map<String, List<String>> query,
                      Map<String, String> headers) {

        /** Each query parameter's first value, the single-valued view requests carry. */
        Map<String, String> singleQuery() {
            Map<String, String> single = new LinkedHashMap<>();
            query.forEach((name, values) -> single.put(name, values.getFirst()));
            return single;
        }
    }

    /** One sub-response: its optional {@code Content-ID} and the response the handler produced for it. */
    record Part(Integer contentId, Response response) {
    }

    /**
     * Parses a batch body into its sub-requests. {@code container} is the batch's own container at container
     * scope, and null at service scope, where sub-requests may name any container of the account.
     * {@code pathAddressed} says how this endpoint addresses a blob, because a sub-request's target repeats
     * the batch endpoint's own addressing: {@code /{account}/{container}/{blob}} by path, and
     * {@code /{container}/{blob}} by host, which is what {@code {account}.blob.core.windows.net} does and what
     * the SDKs derive from each blob URL's path.
     *
     * @throws BlobBatchException when the request is not a usable batch: an unusable {@code Content-Type},
     *         a part that is not an addressed Blob Delete, a sub-request outside the batch's scope, or an
     *         unsupported operation
     */
    static List<SubRequest> parse(String contentType, byte[] body, String account, String container,
                                  boolean pathAddressed) {
        String delimiter = LINE_ENDING + "--" + Pattern.quote(boundary(contentType))
                + "(?=(?:--)?[ \\t]*(?:\\r\\n|$))";
        // Delimiters are whole lines, as RFC 2046 defines them. Matching the boundary text anywhere would cut
        // a target short at a blob name that happens to contain it, and delete the blob its prefix names.
        String[] parts = Pattern.compile(delimiter)
                .split(LINE_ENDING + new String(body, StandardCharsets.UTF_8), -1);

        List<SubRequest> subRequests = new ArrayList<>();
        for (int i = 1; i < parts.length && !parts[i].startsWith("--"); i++) {
            subRequests.add(parsePart(parts[i], account, container, pathAddressed));
            if (subRequests.size() > MAX_SUB_REQUESTS) {
                throw new BlobBatchException(400, "ExceedsMaxBatchRequestCount",
                        "The batch operation exceeds maximum number of allowed subrequests.", Map.of());
            }
        }
        if (subRequests.isEmpty()) {
            throw invalidInput();
        }
        return subRequests;
    }

    /**
     * Serializes the sub-responses as the {@code multipart/mixed} body of the batch's {@code 202 Accepted}:
     * one {@code application/http} part per sub-request, each holding a complete HTTP response.
     */
    static Response accepted(List<Part> parts) {
        String boundary = "batchresponse_" + UUID.randomUUID();
        StringBuilder body = new StringBuilder();
        for (Part part : parts) {
            Response response = part.response();
            body.append("--").append(boundary).append(LINE_ENDING);
            body.append("Content-Type: application/http").append(LINE_ENDING);
            if (part.contentId() != null) {
                body.append("Content-ID: ").append(part.contentId()).append(LINE_ENDING);
            }
            body.append(LINE_ENDING);
            body.append("HTTP/1.1 ").append(response.getStatus()).append(' ')
                    .append(reasonPhrase(response.getStatus())).append(LINE_ENDING);
            response.getStringHeaders().forEach((name, values) -> {
                for (String value : values) {
                    body.append(name).append(": ").append(value).append(LINE_ENDING);
                }
            });
            body.append(LINE_ENDING);
            // Only errors carry a body: a sub-response's success is its status and headers, as in Azure.
            if (response.getEntity() instanceof String entity && !entity.isEmpty()) {
                body.append(entity).append(LINE_ENDING);
            }
        }
        body.append("--").append(boundary).append("--");
        return Response.status(Response.Status.ACCEPTED)
                .type("multipart/mixed; boundary=" + boundary)
                .entity(body.toString())
                .build();
    }

    /**
     * The batch boundary: the {@code boundary} parameter of the {@code Content-Type} header. The parameter
     * name matches case-insensitively, its value may be padded with spaces, and it may be quoted, because MIME
     * quoting is how a boundary carrying a special character is written.
     */
    private static String boundary(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new BlobBatchException(400, "MissingRequiredHeader",
                    "An HTTP header that's mandatory for this request is not specified.",
                    Map.of("HeaderName", "Content-Type"));
        }
        String boundary = null;
        for (String parameter : contentType.split(";")) {
            String trimmed = parameter.trim();
            if (!trimmed.toLowerCase(Locale.ROOT).startsWith(BOUNDARY_PARAMETER)) {
                continue;
            }
            if (boundary != null) {
                throw invalidInput();
            }
            boundary = trimmed.substring(BOUNDARY_PARAMETER.length()).trim();
            if (boundary.length() > 1 && boundary.startsWith("\"") && boundary.endsWith("\"")) {
                boundary = boundary.substring(1, boundary.length() - 1);
            }
        }
        if (boundary == null || boundary.isEmpty()) {
            throw new BlobBatchException(400, "InvalidHeaderValue",
                    "The value for one of the HTTP headers is not in the correct format.",
                    Map.of("HeaderName", "Content-Type", "HeaderValue", contentType));
        }
        return boundary;
    }

    private static SubRequest parsePart(String part, String account, String container, boolean pathAddressed) {
        // A part opens with the delimiter's line ending, then its own headers (setting the Content-ID),
        // a blank line, and finally the embedded HTTP request and that request's headers.
        String[] lines = part.stripLeading().split(LINE_PATTERN, -1);
        int line = 0;
        Integer contentId = null;
        while (line < lines.length && !lines[line].isEmpty()) {
            String[] header = header(lines[line]);
            if (CONTENT_ID.equalsIgnoreCase(header[0])) {
                contentId = contentId(header[1]);
            }
            line++;
        }
        if (contentId == null || ++line >= lines.length) {
            throw invalidInput();
        }

        String[] request = lines[line].trim().split("\\s+");
        // A complete request line, so a target cut short by a broken part is never run as a delete.
        if (request.length != 3 || !request[2].startsWith("HTTP/")) {
            throw invalidInput();
        }
        if (!"DELETE".equalsIgnoreCase(request[0])) {
            throw new BlobBatchException(501, "NotImplemented",
                    "The requested operation is not implemented.", Map.of());
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (line++; line < lines.length && !lines[line].isEmpty(); line++) {
            String[] header = header(lines[line]);
            headers.put(header[0].toLowerCase(Locale.ROOT), header[1]);
        }
        return addressed(contentId, request[1], account, container, pathAddressed, headers);
    }

    /** The blob a sub-request's request target names, once it is known to lie inside the batch's scope. */
    private static SubRequest addressed(int contentId, String target, String account, String container,
                                        boolean pathAddressed, Map<String, String> headers) {
        URI uri;
        try {
            uri = new URI(target.startsWith("/") ? target : "/" + target);
        } catch (URISyntaxException e) {
            throw invalidInput();
        }
        String path = uri.getPath();
        if (path == null) {
            throw invalidInput();
        }
        // The blob name keeps its own slashes: split off exactly the segments the target names, so that
        // {account}/{container}/{blob} yields the blob and {container}/{blob} yields the blob, however many
        // slashes it holds.
        String[] segments = (path.startsWith("/") ? path.substring(1) : path).split("/", pathAddressed ? 3 : 2);
        int nameOffset = 0;
        if (pathAddressed) {
            // A path-addressed endpoint repeats the account in every sub-request target, so the target must
            // name it, and the container follows.
            if (segments.length < 3 || !segments[0].equals(account)) {
                throw invalidInput();
            }
            nameOffset = 1;
        }
        if (segments.length < nameOffset + 2
                || segments[nameOffset].isEmpty() || segments[nameOffset + 1].isEmpty()) {
            throw invalidInput();
        }
        String targetContainer = segments[nameOffset];
        // Azure answers a sub-request addressed outside the batch with 400, and so does this: a batch is one
        // container's (or one account's) work, and a delete must not reach past what was signed.
        if (container != null && !container.equals(targetContainer)) {
            throw invalidInput();
        }
        return new SubRequest(contentId, targetContainer, segments[nameOffset + 1],
                queryParameters(uri.getRawQuery()), headers);
    }

    private static Integer contentId(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw invalidInput();
        }
    }

    /** A part header's name and value, split at the first colon. */
    private static String[] header(String line) {
        int colon = line.indexOf(':');
        if (colon < 1) {
            throw invalidInput();
        }
        return new String[] {line.substring(0, colon).trim(), line.substring(colon + 1).trim()};
    }

    private static Map<String, List<String>> queryParameters(String rawQuery) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            query.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
        return query;
    }

    private static String reasonPhrase(int status) {
        Response.Status known = Response.Status.fromStatusCode(status);
        return known == null ? "" : known.getReasonPhrase();
    }

    private static BlobBatchException invalidInput() {
        return new BlobBatchException(400, "InvalidInput", "One of the request inputs is not valid.", Map.of());
    }

    /**
     * A rejected batch, answered with the Storage error envelope. A batch is rejected whole, unlike a
     * sub-request whose failure is one part of an otherwise successful response.
     */
    static final class BlobBatchException extends RuntimeException {

        private final int status;
        private final String code;
        private final Map<String, String> details;

        BlobBatchException(int status, String code, String message, Map<String, String> details) {
            super(message);
            this.status = status;
            this.code = code;
            this.details = new LinkedHashMap<>(details);
        }

        boolean requiresDirectResponse() {
            return "MissingRequiredHeader".equals(code);
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
}
