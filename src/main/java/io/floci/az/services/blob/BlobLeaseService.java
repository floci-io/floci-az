package io.floci.az.services.blob;

import io.floci.az.core.AzureErrorResponse;
import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blob lease state and the Lease Blob operation (comp=lease).
 *
 * <p>Leases are the coordination primitive of the Azure Functions host:
 * WebJobs singleton/timer locks and Durable Functions partition management
 * are blob leases. Lease state is kept in memory only — like a real lease it
 * is transient runtime state, and an emulator restart is equivalent to every
 * lease having expired.
 */
@ApplicationScoped
public class BlobLeaseService {

    private final Map<String, BlobLease> leases = new ConcurrentHashMap<>();

    /**
     * Dispatch one Lease Blob call for an existing blob. The caller has already
     * resolved the blob and 404s when it is absent; a lease operation never
     * modifies the blob, so its ETag/Last-Modified are echoed unchanged.
     */
    public synchronized Response handleLeaseOp(AzureRequest request, String blobKey,
                                               String etag, String lastModifiedRfc1123) {
        String action = header(request, "x-ms-lease-action");
        Instant now = Instant.now();
        BlobLease lease = leases.get(blobKey);
        Response response = switch (action == null ? "" : action.toLowerCase()) {
            case "acquire" -> acquire(request, blobKey, lease, now);
            case "renew" -> renew(request, blobKey, lease, now);
            case "change" -> change(request, blobKey, lease, now);
            case "release" -> release(request, blobKey, lease, now);
            case "break" -> breakLease(request, blobKey, lease, now);
            default -> invalidHeaderValue();
        };
        if (response.getStatus() >= 400) {
            return response;
        }
        return Response.fromResponse(response)
                .header("ETag", etag)
                .header("Last-Modified", lastModifiedRfc1123)
                .build();
    }

    private Response acquire(AzureRequest request, String blobKey, BlobLease lease, Instant now) {
        int duration;
        try {
            duration = Integer.parseInt(header(request, "x-ms-lease-duration") == null
                    ? "-1" : header(request, "x-ms-lease-duration"));
        } catch (NumberFormatException e) {
            duration = 0;
        }
        if (duration != -1 && (duration < 15 || duration > 60)) {
            return invalidHeaderValue();
        }
        String proposed = header(request, "x-ms-proposed-lease-id");
        if (proposed != null && !isGuid(proposed)) {
            return invalidHeaderValue();
        }

        if (lease != null && lease.activeAt(now)) {
            boolean reacquireSameId = lease.stateAt(now) == BlobLease.State.LEASED
                    && proposed != null && proposed.equalsIgnoreCase(lease.leaseId());
            if (!reacquireSameId) {
                return new AzureErrorResponse("LeaseAlreadyPresent",
                        "There is already a lease present.").toXmlResponse(409);
            }
        }

        String leaseId = proposed != null && !proposed.isBlank() ? proposed : UUID.randomUUID().toString();
        leases.put(blobKey, BlobLease.acquire(leaseId, duration, now));
        return Response.status(201)
                .header("x-ms-lease-id", leaseId)
                .build();
    }

    private Response renew(AzureRequest request, String blobKey, BlobLease lease, Instant now) {
        String leaseId = header(request, "x-ms-lease-id");
        if (leaseId == null || leaseId.isBlank()) {
            return missingRequiredHeader();
        }
        if (lease == null) {
            return leaseNotPresent();
        }
        if (!lease.leaseId().equalsIgnoreCase(leaseId)) {
            return new AzureErrorResponse("LeaseIdMismatchWithLeaseOperation",
                    "The lease ID specified did not match the lease ID for the blob.")
                    .toXmlResponse(409);
        }
        BlobLease.State state = lease.stateAt(now);
        if (state == BlobLease.State.BREAKING || state == BlobLease.State.BROKEN) {
            return new AzureErrorResponse("LeaseIsBrokenAndCannotBeRenewed",
                    "The lease ID matched, but the lease has been broken explicitly and cannot be renewed.")
                    .toXmlResponse(409);
        }
        leases.put(blobKey, lease.renewed(now));
        return Response.ok().header("x-ms-lease-id", lease.leaseId()).build();
    }

    private Response change(AzureRequest request, String blobKey, BlobLease lease, Instant now) {
        String leaseId = header(request, "x-ms-lease-id");
        String proposed = header(request, "x-ms-proposed-lease-id");
        if (leaseId == null || leaseId.isBlank() || proposed == null || proposed.isBlank()) {
            return missingRequiredHeader();
        }
        if (!isGuid(proposed)) {
            return invalidHeaderValue();
        }
        if (lease == null || !lease.activeAt(now)) {
            return leaseNotPresent();
        }
        if (!lease.leaseId().equalsIgnoreCase(leaseId)) {
            return new AzureErrorResponse("LeaseIdMismatchWithLeaseOperation",
                    "The lease ID specified did not match the lease ID for the blob.")
                    .toXmlResponse(409);
        }
        BlobLease changed = lease.changed(proposed);
        leases.put(blobKey, changed);
        return Response.ok().header("x-ms-lease-id", changed.leaseId()).build();
    }

    private Response release(AzureRequest request, String blobKey, BlobLease lease, Instant now) {
        String leaseId = header(request, "x-ms-lease-id");
        if (leaseId == null || leaseId.isBlank()) {
            return missingRequiredHeader();
        }
        if (lease == null) {
            return leaseNotPresent();
        }
        if (!lease.leaseId().equalsIgnoreCase(leaseId)) {
            return new AzureErrorResponse("LeaseIdMismatchWithLeaseOperation",
                    "The lease ID specified did not match the lease ID for the blob.")
                    .toXmlResponse(409);
        }
        leases.remove(blobKey);
        return Response.ok().build();
    }

    private Response breakLease(AzureRequest request, String blobKey, BlobLease lease, Instant now) {
        if (lease == null || !lease.activeAt(now)) {
            return leaseNotPresent();
        }
        int breakPeriod;
        String breakPeriodHeader = header(request, "x-ms-lease-break-period");
        if (breakPeriodHeader != null) {
            try {
                breakPeriod = Integer.parseInt(breakPeriodHeader);
            } catch (NumberFormatException e) {
                return invalidHeaderValue();
            }
            if (breakPeriod < 0 || breakPeriod > 60) {
                return invalidHeaderValue();
            }
        } else {
            // Default: infinite leases break immediately, fixed leases run out their term.
            breakPeriod = lease.expiresAt() == null ? 0
                    : (int) Math.max(0, java.time.Duration.between(now, lease.expiresAt()).getSeconds());
        }
        BlobLease broken = lease.stateAt(now) == BlobLease.State.BREAKING
                ? lease : lease.broken(breakPeriod, now);
        leases.put(blobKey, broken);
        return Response.status(202)
                .header("x-ms-lease-time", String.valueOf(broken.remainingBreakSeconds(now)))
                .build();
    }

    private static Response leaseNotPresent() {
        return new AzureErrorResponse("LeaseNotPresentWithLeaseOperation",
                "There is currently no lease on the blob.").toXmlResponse(409);
    }

    private static Response missingRequiredHeader() {
        return new AzureErrorResponse("MissingRequiredHeader",
                "An HTTP header that's mandatory for this request is not specified.")
                .toXmlResponse(400);
    }

    private static Response invalidHeaderValue() {
        return new AzureErrorResponse("InvalidHeaderValue",
                "The value for one of the HTTP headers is not in the correct format.")
                .toXmlResponse(400);
    }

    private static boolean isGuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Run a blob operation atomically with respect to lease transitions: this
     * holds the same monitor as {@link #handleLeaseOp}, so a competing
     * acquire/break/release cannot interleave with the supplied operation.
     * Every mutation of blob or lease state — including its precondition
     * checks (existence, conditional headers, {@link #validateWrite}) — must
     * run inside this to stay linearized with lease operations and with
     * container deletion sweeps.
     */
    public synchronized Response exclusively(java.util.function.Supplier<Response> operation) {
        return operation.get();
    }

    /**
     * Lease guard for write/delete operations on a blob. Returns null when the
     * operation may proceed, otherwise the 412 the Blob service contract
     * requires. Call only inside {@link #exclusively}.
     */
    Response validateWrite(AzureRequest request, String blobKey) {
        String requestLeaseId = header(request, "x-ms-lease-id");
        BlobLease lease = leases.get(blobKey);
        Instant now = Instant.now();
        if (lease != null && lease.activeAt(now)) {
            if (requestLeaseId == null) {
                return new AzureErrorResponse("LeaseIdMissing",
                        "There is currently a lease on the blob and no lease ID was specified in the request.")
                        .toXmlResponse(412);
            }
            if (!lease.leaseId().equalsIgnoreCase(requestLeaseId)) {
                return new AzureErrorResponse("LeaseIdMismatchWithBlobOperation",
                        "The lease ID specified did not match the lease ID for the blob.")
                        .toXmlResponse(412);
            }
        } else if (requestLeaseId != null) {
            return new AzureErrorResponse("LeaseNotPresentWithBlobOperation",
                    "There is currently no lease on the blob.").toXmlResponse(412);
        }
        return null;
    }

    /** Stamp x-ms-lease-status/-state (+ -duration when leased) with the blob's real lease state. */
    public void addLeaseHeaders(Response.ResponseBuilder rb, String blobKey) {
        BlobLease lease = leases.get(blobKey);
        BlobLease.State state = lease == null ? null : lease.stateAt(Instant.now());
        if (state == null) {
            rb.header("x-ms-lease-status", "unlocked").header("x-ms-lease-state", "available");
            return;
        }
        switch (state) {
            case LEASED -> rb.header("x-ms-lease-status", "locked")
                    .header("x-ms-lease-state", "leased")
                    .header("x-ms-lease-duration", lease.expiresAt() == null ? "infinite" : "fixed");
            case BREAKING -> rb.header("x-ms-lease-status", "locked")
                    .header("x-ms-lease-state", "breaking");
            case EXPIRED -> rb.header("x-ms-lease-status", "unlocked")
                    .header("x-ms-lease-state", "expired");
            case BROKEN -> rb.header("x-ms-lease-status", "unlocked")
                    .header("x-ms-lease-state", "broken");
        }
    }

    public synchronized void onBlobDeleted(String blobKey) {
        leases.remove(blobKey);
    }

    public synchronized void onContainerDeleted(String blobKeyPrefix) {
        leases.keySet().removeIf(k -> k.startsWith(blobKeyPrefix));
    }

    public synchronized void clear() {
        leases.clear();
    }

    private static String header(AzureRequest request, String name) {
        return request.headers().getHeaderString(name);
    }
}
