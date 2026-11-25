package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

/**
 * Blob Batch: {@code POST /{container}?restype=container&comp=batch} and its service-scoped form
 * {@code POST /?restype=service&comp=batch}.
 *
 * <p>The batch itself always answers {@code 202 Accepted} with a {@code multipart/mixed} body: what each
 * object's outcome was lives on that object's own part, so a caller deleting a prefix learns which objects
 * went and which were already gone. These tests pin both halves — the per-part statuses and the batch-level
 * rejections — and check the store afterwards, since a per-part {@code 202} that deleted nothing would read
 * the same from the response alone.
 */
@QuarkusTest
class BlobBatchTest {

    private static final String ACCOUNT = "devstoreaccount1";
    private static final String CONTAINER = "batch-container";
    private static final String KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void batchDeleteRemovesEveryBlobInOneRequest() {
        createContainer(CONTAINER);
        List<String> blobs = List.of("out/a.txt", "out/b.txt", "out/c.txt");
        blobs.forEach(blob -> uploadBlob(CONTAINER, blob));

        String boundary = "batch_multiple";
        Response response = submitInContainer(CONTAINER, Map.of(), boundary, batch(boundary,
                deletePart(boundary, 1, CONTAINER, blobs.get(0)),
                deletePart(boundary, 2, CONTAINER, blobs.get(1)),
                deletePart(boundary, 3, CONTAINER, blobs.get(2))));

        assertBatchAccepted(response, 3);
        List<String> parts = subResponses(response);
        assertThat(parts, everyItem(startsWith("HTTP/1.1 202 Accepted")));
        for (String part : parts) {
            assertThat(part, containsString("x-ms-request-id: "));
            assertThat(part, containsString("x-ms-version: 2021-08-06"));
        }
        // Each sub-response answers its own Content-ID, in the order the sub-requests were sent.
        for (int contentId = 1; contentId <= blobs.size(); contentId++) {
            assertThat(response.asString(),
                    containsString("Content-ID: " + contentId + "\r\n\r\nHTTP/1.1 202 Accepted"));
        }
        blobs.forEach(blob -> assertBlobMissing(CONTAINER, blob));
    }

    @Test
    void batchDeleteReportsAMissingBlobWithoutFailingTheOthers() {
        createContainer(CONTAINER);
        uploadBlob(CONTAINER, "out/present.txt");

        String boundary = "batch_missing";
        Response response = submitInContainer(CONTAINER, Map.of(), boundary, batch(boundary,
                deletePart(boundary, 1, CONTAINER, "out/present.txt"),
                deletePart(boundary, 2, CONTAINER, "out/never-written.txt")));

        assertBatchAccepted(response, 2);
        List<String> parts = subResponses(response);
        assertThat(parts.get(0), startsWith("HTTP/1.1 202 Accepted"));
        // A missing blob is that sub-request's 404, with the error code the SDKs read, and nothing more.
        assertThat(parts.get(1), startsWith("HTTP/1.1 404 Not Found"));
        assertThat(parts.get(1), containsString("x-ms-error-code: BlobNotFound"));
        assertThat(parts.get(1), containsString("<Code>BlobNotFound</Code>"));
        assertBlobMissing(CONTAINER, "out/present.txt");
    }

    @Test
    void batchDeleteAppliesEachSubRequestsOwnHeaders() {
        createContainer(CONTAINER);
        uploadBlob(CONTAINER, "out/snapshot.txt");
        given().header("x-ms-version", "2021-08-06")
                .when().put("/{account}/{container}/{blob}?comp=snapshot", ACCOUNT, CONTAINER, "out/snapshot.txt")
                .then().statusCode(201);

        String boundary = "batch_headers";
        Response response = submitInContainer(CONTAINER, Map.of(), boundary, batch(boundary,
                deletePart(boundary, 1, CONTAINER, "out/snapshot.txt"),
                deletePart(boundary, 2, CONTAINER, "out/snapshot.txt", "x-ms-delete-snapshots: include")));

        assertBatchAccepted(response, 2);
        List<String> parts = subResponses(response);
        // A blob with snapshots needs its sub-request to say what happens to them, as a plain delete does.
        assertThat(parts.get(0), startsWith("HTTP/1.1 409 Conflict"));
        assertThat(parts.get(0), containsString("x-ms-error-code: SnapshotsPresent"));
        assertThat(parts.get(1), startsWith("HTTP/1.1 202 Accepted"));
        assertBlobMissing(CONTAINER, "out/snapshot.txt");
    }

    @Test
    void serviceScopedBatchDeletesAcrossContainers() {
        createContainer("batch-one");
        createContainer("batch-two");
        uploadBlob("batch-one", "a.txt");
        uploadBlob("batch-two", "b.txt");

        String boundary = "batch_service";
        Response response = given()
                .header("Content-Type", "multipart/mixed; boundary=" + boundary)
                .body(bytes(batch(boundary,
                        deletePart(boundary, 1, "batch-one", "a.txt"),
                        deletePart(boundary, 2, "batch-two", "b.txt"))))
                .when().post("/{account}?restype=service&comp=batch", ACCOUNT);

        assertBatchAccepted(response, 2);
        assertThat(subResponses(response), everyItem(startsWith("HTTP/1.1 202 Accepted")));
        assertBlobMissing("batch-one", "a.txt");
        assertBlobMissing("batch-two", "b.txt");
    }

    @Test
    void containerScopedBatchRejectsASubRequestInAnotherContainer() {
        createContainer(CONTAINER);
        createContainer("batch-other");
        uploadBlob("batch-other", "a.txt");

        String boundary = "batch_scope";
        submitInContainer(CONTAINER, Map.of(), boundary, batch(boundary,
                deletePart(boundary, 1, "batch-other", "a.txt")))
                .then().statusCode(400).header("x-ms-error-code", "InvalidInput");

        // A batch is one container's work: the rejected request changed nothing.
        given().when().get("/{account}/{container}/{blob}", ACCOUNT, "batch-other", "a.txt")
                .then().statusCode(200);
    }

    @Test
    void batchDeleteIsAuthorizedByTheBatchsContainerSas() throws Exception {
        createContainer(CONTAINER);
        uploadBlob(CONTAINER, "a.txt");
        uploadBlob(CONTAINER, "b.txt");

        String boundary = "batch_sas";
        Response denied = submitInContainer(CONTAINER, containerSas("r"), boundary, batch(boundary,
                deletePart(boundary, 1, CONTAINER, "a.txt"),
                deletePart(boundary, 2, CONTAINER, "b.txt")));
        // A read-only SAS fails each sub-request on its own part; the batch's own status stays 202.
        assertBatchAccepted(denied, 2);
        assertThat(subResponses(denied),
                everyItem(containsString("x-ms-error-code: AuthorizationPermissionMismatch")));
        given().when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, "a.txt").then().statusCode(200);

        String allowedBoundary = "batch_sas_allowed";
        Response allowed = submitInContainer(CONTAINER, containerSas("rd"), allowedBoundary, batch(allowedBoundary,
                deletePart(allowedBoundary, 1, CONTAINER, "a.txt"),
                deletePart(allowedBoundary, 2, CONTAINER, "b.txt")));
        assertBatchAccepted(allowed, 2);
        assertThat(subResponses(allowed), everyItem(startsWith("HTTP/1.1 202 Accepted")));
        assertBlobMissing(CONTAINER, "a.txt");
        assertBlobMissing(CONTAINER, "b.txt");
    }

    @Test
    void batchRejectsAnOperationItDoesNotImplement() {
        createContainer(CONTAINER);
        uploadBlob(CONTAINER, "a.txt");

        String boundary = "batch_tier";
        submitInContainer(CONTAINER, Map.of(), boundary, batch(boundary,
                part(boundary, 1, "PUT", "/" + ACCOUNT + "/" + CONTAINER + "/a.txt?comp=tier",
                        "x-ms-access-tier: Archive")))
                .then().statusCode(501).header("x-ms-error-code", "NotImplemented");

        // Set Blob Tier is not implemented here, so the batch must not report a success it did not make.
        given().when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, "a.txt").then().statusCode(200);
    }

    @Test
    void batchRejectsMoreThanTheMaximumNumberOfSubRequests() {
        createContainer(CONTAINER);
        String boundary = "batch_limit";
        StringBuilder body = new StringBuilder();
        for (int contentId = 1; contentId <= 257; contentId++) {
            body.append(deletePart(boundary, contentId, CONTAINER, "blob-" + contentId));
        }

        submitInContainer(CONTAINER, Map.of(), boundary, body + "--" + boundary + "--\r\n")
                .then().statusCode(400).header("x-ms-error-code", "ExceedsMaxBatchRequestCount");
    }

    @ParameterizedTest(name = "a Content-Type of \"{0}\" is {1}")
    @CsvSource({
            "multipart/mixed, InvalidHeaderValue",
            "'multipart/mixed; boundary=', InvalidHeaderValue",
            "multipart/mixed; boundary=a; boundary=b, InvalidInput"
    })
    void batchRejectsAnUnusableContentType(String contentType, String errorCode) {
        createContainer(CONTAINER);
        String boundary = "batch_bad_content_type";

        given()
                .header("Content-Type", contentType)
                .body(bytes(batch(boundary, deletePart(boundary, 1, CONTAINER, "a.txt"))))
                .when().post("/{account}/{container}?restype=container&comp=batch", ACCOUNT, CONTAINER)
                .then().statusCode(400).header("x-ms-error-code", errorCode);
    }

    @Test
    void batchRejectsABodyThatUsesAnotherBoundary() {
        createContainer(CONTAINER);
        String boundary = "batch_wrong_boundary";

        submitInContainer(CONTAINER, Map.of(), boundary,
                batch("some_other_boundary", deletePart("some_other_boundary", 1, CONTAINER, "a.txt")))
                .then().statusCode(400).header("x-ms-error-code", "InvalidInput");
    }

    /**
     * Host-addressed endpoints ({@code {account}.blob.core.windows.net}, and the emulator's
     * {@code {account}.blob.localhost}) carry the account in the host, so an SDK's sub-request targets drop it:
     * {@code DELETE /{container}/{blob}}. Gated on {@code Host}, so the account is not in the path.
     */
    @Test
    void hostAddressedBatchDeletesWithTargetsThatOmitTheAccount() {
        createContainer(CONTAINER);
        uploadBlob(CONTAINER, "a.txt");

        String boundary = "batch_host";
        Response response = given()
                .header("Host", ACCOUNT + ".blob.core.windows.net")
                .header("Content-Type", "multipart/mixed; boundary=" + boundary)
                .body(bytes(batch(boundary,
                        part(boundary, 1, "DELETE", "/" + CONTAINER + "/a.txt"))))
                .when().post("/{container}?restype=container&comp=batch", CONTAINER);

        assertBatchAccepted(response, 1);
        assertThat(subResponses(response), everyItem(startsWith("HTTP/1.1 202 Accepted")));
        assertBlobMissing(CONTAINER, "a.txt");
    }

    @Test
    void batchAcceptsAQuotedBoundary() {
        createContainer(CONTAINER);
        uploadBlob(CONTAINER, "a.txt");

        String boundary = "batch_quoted";
        Response response = given()
                .header("Content-Type", "multipart/mixed; boundary=\"" + boundary + "\"")
                .body(bytes(batch(boundary, deletePart(boundary, 1, CONTAINER, "a.txt"))))
                .when().post("/{account}/{container}?restype=container&comp=batch", ACCOUNT, CONTAINER);

        assertBatchAccepted(response, 1);
        assertBlobMissing(CONTAINER, "a.txt");
    }

    /**
     * A blob name that contains the boundary text must not be read as a delimiter: cutting the target short
     * there would delete the blob its prefix names, which is not the blob the caller asked for.
     */
    @Test
    void aBlobNameContainingTheBoundaryIsNotReadAsADelimiter() {
        createContainer(CONTAINER);
        String boundary = "batch_trap";
        String trapped = "victim--" + boundary + "--suffix";
        uploadBlob(CONTAINER, "victim");
        uploadBlob(CONTAINER, trapped);

        Response response = submitInContainer(CONTAINER, Map.of(), boundary,
                batch(boundary, deletePart(boundary, 1, CONTAINER, trapped)));

        assertBatchAccepted(response, 1);
        assertThat(subResponses(response), everyItem(startsWith("HTTP/1.1 202 Accepted")));
        assertBlobMissing(CONTAINER, trapped);
        given().when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, "victim").then().statusCode(200);
    }

    /** A nested blob name keeps every slash on the host-addressed endpoint too, which drops the account only. */
    @Test
    void hostAddressedBatchKeepsTheSlashesOfANestedBlobName() {
        createContainer(CONTAINER);
        uploadBlob(CONTAINER, "dir");
        uploadBlob(CONTAINER, "dir/file.txt");

        String boundary = "batch_host_nested";
        Response response = given()
                .header("Host", ACCOUNT + ".blob.core.windows.net")
                .header("Content-Type", "multipart/mixed; boundary=" + boundary)
                .body(bytes(batch(boundary,
                        part(boundary, 1, "DELETE", "/" + CONTAINER + "/dir/file.txt"))))
                .when().post("/{container}?restype=container&comp=batch", CONTAINER);

        assertBatchAccepted(response, 1);
        assertThat(subResponses(response), everyItem(startsWith("HTTP/1.1 202 Accepted")));
        assertBlobMissing(CONTAINER, "dir/file.txt");
        // The blob named "dir" is not the one the sub-request asked for.
        given().when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, "dir").then().statusCode(200);
    }

    private static void createContainer(String container) {
        given().put("/{account}/{container}?restype=container", ACCOUNT, container).then().statusCode(201);
    }

    private static void uploadBlob(String container, String blob) {
        given()
                .header("x-ms-blob-type", "BlockBlob")
                .contentType("text/plain")
                .body("content of " + blob)
                .when().put("/{account}/{container}/{blob}", ACCOUNT, container, blob)
                .then().statusCode(201);
    }

    private static void assertBlobMissing(String container, String blob) {
        given().when().get("/{account}/{container}/{blob}", ACCOUNT, container, blob).then().statusCode(404);
    }

    private static Response submitInContainer(String container, Map<String, String> sas, String boundary,
                                              String body) {
        return given()
                .queryParams(sas)
                .header("Content-Type", "multipart/mixed; boundary=" + boundary)
                .body(bytes(body))
                .when().post("/{account}/{container}?restype=container&comp=batch", ACCOUNT, container);
    }

    private static void assertBatchAccepted(Response response, int expectedParts) {
        response.then()
                .statusCode(202)
                .contentType(startsWith("multipart/mixed; boundary=batchresponse_"));
        assertThat(subResponses(response), hasSize(expectedParts));
    }

    /**
     * The sub-responses of a batch response, in order: for each part, everything from its own status line
     * to the next part — so the last one keeps the closing delimiter, which no assertion depends on.
     */
    private static List<String> subResponses(Response response) {
        String raw = response.asString();
        List<String> subResponses = new ArrayList<>();
        int contentId = raw.indexOf("Content-ID: ");
        while (contentId >= 0) {
            int statusLine = raw.indexOf("\r\n\r\n", contentId);
            int next = raw.indexOf("Content-ID: ", statusLine);
            subResponses.add(raw.substring(statusLine + 4, next < 0 ? raw.length() : next));
            contentId = next;
        }
        return subResponses;
    }

    private static String deletePart(String boundary, int contentId, String container, String blob,
                                     String... headers) {
        return part(boundary, contentId, "DELETE", "/" + ACCOUNT + "/" + container + "/" + blob, headers);
    }

    /** One multipart part as the SDKs assemble it: its own headers, a blank line, then the request. */
    private static String part(String boundary, int contentId, String method, String target, String... headers) {
        StringBuilder part = new StringBuilder();
        part.append("--").append(boundary).append("\r\n");
        part.append("Content-Type: application/http\r\n");
        part.append("Content-Transfer-Encoding: binary\r\n");
        part.append("Content-ID: ").append(contentId).append("\r\n");
        part.append("\r\n");
        part.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
        part.append("x-ms-date: ").append(Instant.now()).append("\r\n");
        part.append("x-ms-version: 2021-08-06\r\n");
        for (String header : headers) {
            part.append(header).append("\r\n");
        }
        part.append("\r\n");
        return part.toString();
    }

    /** The batch body as raw bytes: RestAssured sends those with the Content-Type given, as a client would. */
    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String batch(String boundary, String... parts) {
        return String.join("", parts) + "--" + boundary + "--\r\n";
    }

    /** A container service SAS for {@link #CONTAINER}, signed the way the SDKs sign one. */
    private static Map<String, String> containerSas(String permissions) throws Exception {
        String version = "2020-12-06";
        String expiry = Instant.now().plusSeconds(3600).toString();
        String fields = String.join("\n",
                permissions,
                "",                                  // signed start time
                expiry,
                "/blob/" + ACCOUNT + "/" + CONTAINER,
                "", "", "",                          // identifier, IP range, protocol
                version,
                "c",                                 // signed resource: the container
                "",                                  // snapshot time
                "",                                  // encryption scope
                "", "", "", "", "");                 // cache-control, disposition, encoding, language, content type
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(KEY), "HmacSHA256"));

        Map<String, String> query = new LinkedHashMap<>();
        query.put("sv", version);
        query.put("sp", permissions);
        query.put("sr", "c");
        query.put("se", expiry);
        query.put("sig", Base64.getEncoder().encodeToString(mac.doFinal(fields.getBytes(StandardCharsets.UTF_8))));
        return query;
    }
}
