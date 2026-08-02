package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Blob lease operations (comp=lease): the backplane the Azure Functions host
 * depends on — WebJobs singleton/timer locks and Durable Functions partition
 * leases are all blob leases (see floci-io/floci-az#136).
 */
@QuarkusTest
public class BlobLeaseTest {

    private static final String ACCOUNT = "devstoreaccount1";
    private static final String CONTAINER = "lease-container";
    private static final String BLOB = "lock-blob";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER)
            .then().statusCode(201);
        given().body("lock").put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(201);
    }

    private Response leaseOp(String action, String... headers) {
        var spec = given().header("x-ms-lease-action", action);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            spec = spec.header(headers[i], headers[i + 1]);
        }
        return spec.put("/{account}/{container}/{blob}?comp=lease", ACCOUNT, CONTAINER, BLOB);
    }

    private String acquire() {
        Response r = leaseOp("acquire", "x-ms-lease-duration", "-1");
        r.then().statusCode(201);
        return r.header("x-ms-lease-id");
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Test
    void acquireReturnsLeaseIdAndLocksBlob() {
        Response r = leaseOp("acquire", "x-ms-lease-duration", "-1");
        r.then().statusCode(201);
        assertThat(r.header("x-ms-lease-id"), not(emptyOrNullString()));

        given().head("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then()
            .header("x-ms-lease-status", equalTo("locked"))
            .header("x-ms-lease-state", equalTo("leased"))
            .header("x-ms-lease-duration", equalTo("infinite"));
    }

    @Test
    void acquireWithProposedIdReturnsProposedIdAndIsIdempotent() {
        String proposed = "11111111-2222-3333-4444-555555555555";
        leaseOp("acquire", "x-ms-lease-duration", "-1", "x-ms-proposed-lease-id", proposed)
            .then().statusCode(201).header("x-ms-lease-id", equalTo(proposed));

        // Re-acquire with the same proposed id succeeds (the SDK retries this way).
        leaseOp("acquire", "x-ms-lease-duration", "-1", "x-ms-proposed-lease-id", proposed)
            .then().statusCode(201).header("x-ms-lease-id", equalTo(proposed));
    }

    @Test
    void acquireWhenLeasedReturnsLeaseAlreadyPresent() {
        acquire();
        leaseOp("acquire", "x-ms-lease-duration", "-1")
            .then().statusCode(409).header("x-ms-error-code", equalTo("LeaseAlreadyPresent"));
    }

    @Test
    void acquireWithInvalidDurationReturnsInvalidHeaderValue() {
        leaseOp("acquire", "x-ms-lease-duration", "5")
            .then().statusCode(400).header("x-ms-error-code", equalTo("InvalidHeaderValue"));
    }

    @Test
    void acquireOnMissingBlobReturnsBlobNotFound() {
        given().header("x-ms-lease-action", "acquire").header("x-ms-lease-duration", "-1")
            .put("/{account}/{container}/absent?comp=lease", ACCOUNT, CONTAINER)
            .then().statusCode(404).header("x-ms-error-code", equalTo("BlobNotFound"));
    }

    @Test
    void renewWithCorrectIdSucceedsWrongIdConflicts() {
        String id = acquire();
        leaseOp("renew", "x-ms-lease-id", id)
            .then().statusCode(200).header("x-ms-lease-id", equalTo(id));
        leaseOp("renew", "x-ms-lease-id", "99999999-9999-9999-9999-999999999999")
            .then().statusCode(409)
            .header("x-ms-error-code", equalTo("LeaseIdMismatchWithLeaseOperation"));
    }

    @Test
    void renewWithoutActiveLeaseReturnsLeaseNotPresent() {
        leaseOp("renew", "x-ms-lease-id", "99999999-9999-9999-9999-999999999999")
            .then().statusCode(409)
            .header("x-ms-error-code", equalTo("LeaseNotPresentWithLeaseOperation"));
    }

    @Test
    void releaseUnlocksAndAllowsReacquire() {
        String id = acquire();
        leaseOp("release", "x-ms-lease-id", id).then().statusCode(200);

        given().head("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then()
            .header("x-ms-lease-status", equalTo("unlocked"))
            .header("x-ms-lease-state", equalTo("available"));

        Response r = leaseOp("acquire", "x-ms-lease-duration", "-1");
        r.then().statusCode(201);
        assertThat(r.header("x-ms-lease-id"), not(equalTo(id)));
    }

    @Test
    void changeLeaseSwapsToProposedId() {
        String id = acquire();
        String proposed = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        leaseOp("change", "x-ms-lease-id", id, "x-ms-proposed-lease-id", proposed)
            .then().statusCode(200).header("x-ms-lease-id", equalTo(proposed));

        // Old id no longer renews; new id does.
        leaseOp("renew", "x-ms-lease-id", id)
            .then().statusCode(409)
            .header("x-ms-error-code", equalTo("LeaseIdMismatchWithLeaseOperation"));
        leaseOp("renew", "x-ms-lease-id", proposed).then().statusCode(200);
    }

    @Test
    void breakLeaseAllowsReacquireAndBlocksRenew() {
        String id = acquire();
        Response broken = leaseOp("break");
        broken.then().statusCode(202);
        assertThat(broken.header("x-ms-lease-time"), equalTo("0"));

        leaseOp("renew", "x-ms-lease-id", id)
            .then().statusCode(409)
            .header("x-ms-error-code", equalTo("LeaseIsBrokenAndCannotBeRenewed"));

        leaseOp("acquire", "x-ms-lease-duration", "-1").then().statusCode(201);
    }

    @Test
    void breakWithoutActiveLeaseReturnsLeaseNotPresent() {
        leaseOp("break")
            .then().statusCode(409)
            .header("x-ms-error-code", equalTo("LeaseNotPresentWithLeaseOperation"));
    }

    // ── Write guards ─────────────────────────────────────────────────────────

    @Test
    void writeToLeasedBlobRequiresLeaseId() {
        String id = acquire();

        given().body("update")
            .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(412).header("x-ms-error-code", equalTo("LeaseIdMissing"));

        given().body("update").header("x-ms-lease-id", "99999999-9999-9999-9999-999999999999")
            .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(412)
            .header("x-ms-error-code", equalTo("LeaseIdMismatchWithBlobOperation"));

        given().body("update").header("x-ms-lease-id", id)
            .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(201);
    }

    @Test
    void writeWithLeaseIdWhenNotLeasedReturnsLeaseNotPresent() {
        given().body("update").header("x-ms-lease-id", "99999999-9999-9999-9999-999999999999")
            .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(412)
            .header("x-ms-error-code", equalTo("LeaseNotPresentWithBlobOperation"));
    }

    @Test
    void setMetadataAndDeleteHonorLeaseGuards() {
        String id = acquire();

        given().header("x-ms-meta-owner", "host-a")
            .put("/{account}/{container}/{blob}?comp=metadata", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(412).header("x-ms-error-code", equalTo("LeaseIdMissing"));

        given().header("x-ms-meta-owner", "host-a").header("x-ms-lease-id", id)
            .put("/{account}/{container}/{blob}?comp=metadata", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(200);

        given().delete("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(412).header("x-ms-error-code", equalTo("LeaseIdMissing"));

        given().header("x-ms-lease-id", id)
            .delete("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(202);

        // Lease dies with the blob: recreate and a fresh acquire must succeed.
        given().body("lock").put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(201);
        leaseOp("acquire", "x-ms-lease-duration", "-1").then().statusCode(201);
    }

    @Test
    void readsRemainAllowedWhileLeased() {
        acquire();
        given().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(200).body(equalTo("lock"));
    }
}
