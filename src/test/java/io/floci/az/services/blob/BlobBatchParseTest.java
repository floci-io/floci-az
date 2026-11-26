package io.floci.az.services.blob;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batch request shapes a transport cannot produce: an HTTP client always sets some
 * {@code Content-Type}, and some of them set one that is no batch at all. Everything reachable over the
 * wire is covered by {@code io.floci.az.services.BlobBatchTest} instead.
 */
class BlobBatchParseTest {

    private static final String CONTENT_TYPE = "multipart/mixed; boundary=batch_1";
    private static final String ACCOUNT = "devstoreaccount1";
    private static final String CONTAINER = "container";

    @Test
    void aBatchWithoutAContentTypeIsAMissingRequiredHeader() {
        byte[] body = body(part("Content-ID: 1", "DELETE /" + ACCOUNT + "/" + CONTAINER + "/blob HTTP/1.1"));

        for (String contentType : new String[] {null, "", "   "}) {
            BlobBatch.BlobBatchException exception = assertThrows(BlobBatch.BlobBatchException.class,
                    () -> BlobBatch.parse(contentType, body, ACCOUNT, CONTAINER, true));
            assertTrue(exception.requiresDirectResponse());
            Response response = exception.toResponse();
            assertEquals(400, response.getStatus());
            assertEquals("MissingRequiredHeader", response.getHeaderString("x-ms-error-code"));
            assertTrue(((String) response.getEntity()).contains("<HeaderName>Content-Type</HeaderName>"),
                    (String) response.getEntity());
        }
    }

    @Test
    void aSubRequestWithoutAContentIdIsInvalidInput() {
        byte[] body = body(part("Content-Type: application/http",
                "DELETE /" + ACCOUNT + "/" + CONTAINER + "/blob HTTP/1.1"));

        Response response = rejected(() -> BlobBatch.parse(CONTENT_TYPE, body, ACCOUNT, CONTAINER, true));

        assertEquals(400, response.getStatus());
        assertEquals("InvalidInput", response.getHeaderString("x-ms-error-code"));
    }

    @Test
    void aSubRequestTargetIsDecodedIntoAContainerAndABlobName() {
        byte[] body = body(part("Content-ID: 7", "DELETE /" + ACCOUNT + "/" + CONTAINER
                + "/dir/blob%20name.txt?x-ms-delete-snapshots=include&snapshot=2020-01-01T00%3A00%3A00Z HTTP/1.1"));

        List<BlobBatch.SubRequest> subRequests = BlobBatch.parse(CONTENT_TYPE, body, ACCOUNT, CONTAINER, true);

        assertEquals(1, subRequests.size());
        BlobBatch.SubRequest subRequest = subRequests.getFirst();
        assertEquals(7, subRequest.contentId());
        assertEquals(CONTAINER, subRequest.container());
        // A blob's own slashes and spaces survive: only the account, container and blob are split apart.
        assertEquals("dir/blob name.txt", subRequest.blob());
        assertEquals("2020-01-01T00:00:00Z", subRequest.singleQuery().get("snapshot"));
        assertEquals("include", subRequest.singleQuery().get("x-ms-delete-snapshots"));
    }

    @Test
    void aSubRequestAddressedOutsideTheAccountIsInvalidInput() {
        byte[] body = body(part("Content-ID: 1",
                "DELETE /otheraccount/" + CONTAINER + "/blob HTTP/1.1"));

        Response response = rejected(() -> BlobBatch.parse(CONTENT_TYPE, body, ACCOUNT, CONTAINER, true));

        assertEquals(400, response.getStatus());
        assertEquals("InvalidInput", response.getHeaderString("x-ms-error-code"));
    }

    @Test
    void aSubRequestWithoutACompleteRequestLineIsInvalidInput() {
        byte[] body = body(part("Content-ID: 1", "DELETE /" + ACCOUNT + "/" + CONTAINER + "/blob"));

        Response response = rejected(() -> BlobBatch.parse(CONTENT_TYPE, body, ACCOUNT, CONTAINER, true));

        assertEquals(400, response.getStatus());
        assertEquals("InvalidInput", response.getHeaderString("x-ms-error-code"));
    }

    @Test
    void aHostAddressedBatchRejectsASubRequestThatRepeatsTheAccount() {
        byte[] body = body(part("Content-ID: 1",
                "DELETE /" + ACCOUNT + "/" + CONTAINER + "/blob HTTP/1.1"));

        Response response = rejected(() -> BlobBatch.parse(CONTENT_TYPE, body, ACCOUNT, CONTAINER, false));

        assertEquals(400, response.getStatus());
        assertEquals("InvalidInput", response.getHeaderString("x-ms-error-code"));
    }

    @Test
    void aHostAddressedTargetKeepsTheSlashesOfTheBlobName() {
        byte[] body = body(part("Content-ID: 1",
                "DELETE /" + CONTAINER + "/dir/file.txt HTTP/1.1"));

        List<BlobBatch.SubRequest> subRequests = BlobBatch.parse(CONTENT_TYPE, body, ACCOUNT, CONTAINER, false);

        assertEquals(1, subRequests.size());
        assertEquals(CONTAINER, subRequests.getFirst().container());
        // The whole name, not its first segment: the blob is "dir/file.txt", never "dir".
        assertEquals("dir/file.txt", subRequests.getFirst().blob());
    }

    @Test
    void aBoundaryPrefixOnItsOwnLineIsNotTreatedAsADelimiter() {
        String first = part("Content-ID: 1",
                "DELETE /" + ACCOUNT + "/" + CONTAINER + "/first HTTP/1.1");
        String boundaryPrefix = "--batch_1-suffix\r\n";
        String second = part("Content-ID: 2",
                "DELETE /" + ACCOUNT + "/" + CONTAINER + "/second HTTP/1.1");
        byte[] body = (first + boundaryPrefix + second + "--batch_1--\r\n")
                .getBytes(StandardCharsets.UTF_8);

        List<BlobBatch.SubRequest> subRequests = BlobBatch.parse(CONTENT_TYPE, body, ACCOUNT, CONTAINER, true);

        assertEquals(2, subRequests.size());
        assertEquals("first", subRequests.getFirst().blob());
        assertEquals("second", subRequests.getLast().blob());
    }

    private static Response rejected(Runnable parse) {
        return assertThrows(BlobBatch.BlobBatchException.class, parse::run).toResponse();
    }

    private static byte[] body(String part) {
        return (part + "--batch_1--\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** One multipart part: its own headers, a blank line, the request line, then the request headers. */
    private static String part(String partHeaders, String requestLine) {
        return "--batch_1\r\n" + partHeaders + "\r\n\r\n" + requestLine + "\r\n\r\n";
    }
}
