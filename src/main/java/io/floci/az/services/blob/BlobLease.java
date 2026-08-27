package io.floci.az.services.blob;

import java.time.Instant;

/**
 * State of one blob lease. Immutable; transitions return a new instance.
 *
 * <p>Time is always passed in by the caller so the state machine stays a pure
 * function of (lease, now) — expiry and break-elapse never mutate anything.
 *
 * @param leaseId         current lease id (a GUID)
 * @param durationSeconds fixed duration 15–60, or -1 for infinite
 * @param expiresAt       when a fixed lease lapses; null for infinite leases
 * @param breakAt         when a broken lease's break period ends; null unless broken
 */
public record BlobLease(String leaseId, int durationSeconds, Instant expiresAt, Instant breakAt) {

    public enum State { LEASED, EXPIRED, BREAKING, BROKEN }

    public static BlobLease acquire(String leaseId, int durationSeconds, Instant now) {
        Instant expiresAt = durationSeconds < 0 ? null : now.plusSeconds(durationSeconds);
        return new BlobLease(leaseId, durationSeconds, expiresAt, null);
    }

    public BlobLease renewed(Instant now) {
        return acquire(leaseId, durationSeconds, now);
    }

    public BlobLease changed(String newLeaseId) {
        return new BlobLease(newLeaseId, durationSeconds, expiresAt, breakAt);
    }

    public BlobLease broken(int breakPeriodSeconds, Instant now) {
        Instant breakAt = now.plusSeconds(breakPeriodSeconds);
        // The break period is used only if it is shorter than the lease's
        // remaining time; a fixed lease never outlives its natural expiry.
        if (expiresAt != null && expiresAt.isBefore(breakAt)) {
            breakAt = expiresAt;
        }
        return new BlobLease(leaseId, durationSeconds, expiresAt, breakAt);
    }

    public State stateAt(Instant now) {
        if (breakAt != null) {
            return now.isBefore(breakAt) ? State.BREAKING : State.BROKEN;
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            return State.EXPIRED;
        }
        return State.LEASED;
    }

    /** An active lease blocks writes and competing acquires. */
    public boolean activeAt(Instant now) {
        State s = stateAt(now);
        return s == State.LEASED || s == State.BREAKING;
    }

    /** Seconds until an in-progress break completes (the x-ms-lease-time of a Break response). */
    public long remainingBreakSeconds(Instant now) {
        if (breakAt == null || !now.isBefore(breakAt)) {
            return 0;
        }
        return java.time.Duration.between(now, breakAt).getSeconds();
    }
}
