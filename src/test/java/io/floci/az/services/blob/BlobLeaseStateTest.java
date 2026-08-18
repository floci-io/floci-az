package io.floci.az.services.blob;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Pure state-machine tests: expiry and break timing as a function of (lease, now). */
class BlobLeaseStateTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String ID = "11111111-2222-3333-4444-555555555555";

    @Test
    void infiniteLeaseStaysLeased() {
        BlobLease lease = BlobLease.acquire(ID, -1, T0);
        assertEquals(BlobLease.State.LEASED, lease.stateAt(T0.plusSeconds(999_999)));
        assertTrue(lease.activeAt(T0.plusSeconds(999_999)));
    }

    @Test
    void fixedLeaseExpiresAfterDuration() {
        BlobLease lease = BlobLease.acquire(ID, 15, T0);
        assertEquals(BlobLease.State.LEASED, lease.stateAt(T0.plusSeconds(14)));
        assertEquals(BlobLease.State.EXPIRED, lease.stateAt(T0.plusSeconds(15)));
        assertFalse(lease.activeAt(T0.plusSeconds(15)));
    }

    @Test
    void renewResetsExpiry() {
        BlobLease lease = BlobLease.acquire(ID, 15, T0).renewed(T0.plusSeconds(14));
        assertEquals(BlobLease.State.LEASED, lease.stateAt(T0.plusSeconds(20)));
        assertEquals(BlobLease.State.EXPIRED, lease.stateAt(T0.plusSeconds(29)));
    }

    @Test
    void breakWithZeroPeriodIsImmediatelyBroken() {
        BlobLease lease = BlobLease.acquire(ID, -1, T0).broken(0, T0);
        assertEquals(BlobLease.State.BROKEN, lease.stateAt(T0));
        assertFalse(lease.activeAt(T0));
        assertEquals(0, lease.remainingBreakSeconds(T0));
    }

    @Test
    void breakWithPeriodIsBreakingUntilElapsed() {
        BlobLease lease = BlobLease.acquire(ID, -1, T0).broken(10, T0);
        assertEquals(BlobLease.State.BREAKING, lease.stateAt(T0.plusSeconds(9)));
        assertTrue(lease.activeAt(T0.plusSeconds(9)));
        assertEquals(10, lease.remainingBreakSeconds(T0));
        assertEquals(BlobLease.State.BROKEN, lease.stateAt(T0.plusSeconds(10)));
        assertFalse(lease.activeAt(T0.plusSeconds(10)));
    }

    @Test
    void breakPeriodLongerThanRemainingTimeCapsAtNaturalExpiry() {
        // 15s lease, broken at t+10 with a 60s period: the break period is used
        // only if it is shorter than the remaining lease time, so the lease
        // must be BROKEN at its natural expiry (t+15), not at t+70.
        BlobLease lease = BlobLease.acquire(ID, 15, T0).broken(60, T0.plusSeconds(10));
        assertEquals(BlobLease.State.BREAKING, lease.stateAt(T0.plusSeconds(14)));
        assertEquals(5, lease.remainingBreakSeconds(T0.plusSeconds(10)));
        assertEquals(BlobLease.State.BROKEN, lease.stateAt(T0.plusSeconds(15)));
        assertFalse(lease.activeAt(T0.plusSeconds(15)));
    }

    @Test
    void breakPeriodShorterThanRemainingTimeIsUsed() {
        BlobLease lease = BlobLease.acquire(ID, 60, T0).broken(5, T0);
        assertEquals(BlobLease.State.BREAKING, lease.stateAt(T0.plusSeconds(4)));
        assertEquals(BlobLease.State.BROKEN, lease.stateAt(T0.plusSeconds(5)));
    }

    @Test
    void changeKeepsTimingButSwapsId() {
        BlobLease lease = BlobLease.acquire(ID, 15, T0).changed("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", lease.leaseId());
        assertEquals(BlobLease.State.EXPIRED, lease.stateAt(T0.plusSeconds(15)));
    }
}
