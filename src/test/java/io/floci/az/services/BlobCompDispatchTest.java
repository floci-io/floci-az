package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Guards the blob dispatch against the catch-all {@code PUT} that used to fall through to
 * {@code putBlob} for every unrecognised {@code comp} value.
 *
 * <p>Azure's blob endpoint multiplexes many operations onto {@code PUT /{container}/{blob}},
 * discriminated by {@code comp} (lease, snapshot, properties, tier, tags, page, appendblock) or by
 * a header ({@code x-ms-copy-source}). None of those are implemented here. Before this guard they
 * were all routed to {@code putBlob}, which <em>replaced the blob with the request body</em> —
 * usually empty — and answered {@code 201 Created}. The SDK saw success while the data was gone.
 *
 * <p>These tests pin the two invariants that matter: an unimplemented operation must (1) not be
 * mistaken for a PutBlob, and (2) leave existing content untouched. Implementing any of these
 * operations for real should replace the corresponding {@code 501} expectation — not delete it.
 */
@QuarkusTest
public class BlobCompDispatchTest {

    private static final String ACCOUNT = "devstoreaccount1";
    private static final String CONTAINER = "comp-dispatch";
    private static final String BLOB = "victim.txt";
    private static final String CONTENT = "original content";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER)
                .then().statusCode(201);
        given()
                .header("x-ms-blob-type", "BlockBlob")
                .contentType("text/plain")
                .body(CONTENT)
                .when().put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
                .then().statusCode(201);
    }

    private void assertBlobIntact() {
        given()
                .when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
                .then().statusCode(200).body(equalTo(CONTENT));
    }

    /**
     * Every {@code comp} Azure defines on {@code PUT /{container}/{blob}} that floci-az does not
     * implement. Each one used to be answered by putBlob with a 201 and an empty body.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "snapshot", "properties", "tier", "tags",
            "page", "appendblock", "undelete", "expiry", "seal"
    })
    void unimplementedBlobCompIsNotMistakenForPutBlob(String comp) {
        given()
                .when().put("/{account}/{container}/{blob}?comp={comp}", ACCOUNT, CONTAINER, BLOB, comp)
                .then().statusCode(501);

        assertBlobIntact();
    }

    @Test
    void copyBlobIsNotMistakenForPutBlob() {
        given()
                .header("x-ms-copy-source",
                        "http://127.0.0.1:4577/" + ACCOUNT + "/" + CONTAINER + "/source.txt")
                .when().put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, "copy-target.txt")
                .then().statusCode(501);

        // The destination must not exist: the old behaviour created an empty blob and reported 201.
        given()
                .when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, "copy-target.txt")
                .then().statusCode(404);
    }

    @Test
    void datalakeRenameIsNotMistakenForPutBlob() {
        given()
                .header("x-ms-rename-source", "/" + CONTAINER + "/" + BLOB)
                .when().put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, "renamed.txt")
                .then().statusCode(501);

        assertBlobIntact();
        given()
                .when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, "renamed.txt")
                .then().statusCode(404);
    }

    /**
     * Container-level ops are discriminated the same way, but the old ladder tested
     * {@code restype=container} <em>before</em> any {@code comp}, so SetContainerMetadata and
     * SetContainerAccessPolicy both landed in createContainer and answered 409 — a hard failure for
     * {@code containerClient.setMetadata()}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"metadata", "acl", "lease"})
    void unimplementedContainerCompIsNotMistakenForCreateContainer(String comp) {
        given()
                .when().put("/{account}/{container}?restype=container&comp={comp}",
                        ACCOUNT, CONTAINER, comp)
                .then().statusCode(501);
    }

    /**
     * GetContainerAccessPolicy used to fall to getContainer and answer 200 with an empty body,
     * which the SDK then failed to deserialise as SignedIdentifiers.
     */
    @ParameterizedTest
    @ValueSource(strings = {"acl", "metadata"})
    void unimplementedContainerCompOnGetIsNotMistakenForGetContainer(String comp) {
        given()
                .when().get("/{account}/{container}?restype=container&comp={comp}",
                        ACCOUNT, CONTAINER, comp)
                .then().statusCode(501);
    }

    @Test
    void dataLakeListPathsDoesNotClaimUnimplementedCompOperation() {
        given()
                .header("Host", ACCOUNT + ".dfs.core.windows.net")
                .when().get("/{container}?resource=filesystem&recursive=true&comp=acl", CONTAINER)
                .then().statusCode(501);
    }

    // --- the operations that ARE implemented must keep working ---

    @Test
    void putBlobStillWorks() {
        given()
                .header("x-ms-blob-type", "BlockBlob")
                .contentType("text/plain")
                .body("replacement")
                .when().put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
                .then().statusCode(201);

        given()
                .when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
                .then().statusCode(200).body(equalTo("replacement"));
    }

    @Test
    void setBlobMetadataStillWorks() {
        given()
                .header("x-ms-meta-owner", "compat")
                .when().put("/{account}/{container}/{blob}?comp=metadata", ACCOUNT, CONTAINER, BLOB)
                .then().statusCode(200);

        given()
                .when().get("/{account}/{container}/{blob}?comp=metadata", ACCOUNT, CONTAINER, BLOB)
                .then().statusCode(200).header("x-ms-meta-owner", "compat");

        assertBlobIntact();
    }

    @Test
    void getContainerPropertiesStillWorks() {
        given()
                .when().get("/{account}/{container}?restype=container", ACCOUNT, CONTAINER)
                .then().statusCode(200);
    }

    @Test
    void listBlobsStillWorks() {
        given()
                .when().get("/{account}/{container}?restype=container&comp=list", ACCOUNT, CONTAINER)
                .then().statusCode(200);
    }
}
