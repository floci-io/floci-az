package io.floci.az.core.docker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PortAllocator — claiming a specific configured port")
class PortAllocatorTest {

    @Test
    @DisplayName("no preference: 0 and negative ports claim nothing")
    void noPreferenceClaimsNothing() {
        PortAllocator allocator = new PortAllocator();
        assertEquals(0, allocator.claimOrZero(0));
        assertEquals(0, allocator.claimOrZero(-1));
    }

    @Test
    @DisplayName("a free port is claimed, and only once")
    void secondClaimOfTheSamePortFallsBack() {
        PortAllocator allocator = new PortAllocator();
        int port = allocator.allocateAny();
        allocator.release(port);

        assertEquals(port, allocator.claimOrZero(port), "the first server should get its configured port");
        assertEquals(0, allocator.claimOrZero(port), "a second server must fall back rather than collide");
    }

    @Test
    @DisplayName("releasing lets the same port be claimed again")
    void releaseAllowsReclaim() {
        PortAllocator allocator = new PortAllocator();
        int port = allocator.allocateAny();
        allocator.release(port);

        assertEquals(port, allocator.claimOrZero(port));
        allocator.release(port);
        assertEquals(port, allocator.claimOrZero(port),
            "deleting and recreating a server must be able to reclaim its configured port");
    }

    @Test
    @DisplayName("a port held by another process is not claimed")
    void portHeldElsewhereFallsBack() throws IOException {
        PortAllocator allocator = new PortAllocator();
        try (ServerSocket occupied = new ServerSocket(0)) {
            assertEquals(0, allocator.claimOrZero(occupied.getLocalPort()),
                "claiming a port an unrelated process holds would fail at container start");
        }
    }

    @Test
    @DisplayName("a claim does not get handed out again by range allocation")
    void claimIsVisibleToRangeAllocation() {
        PortAllocator allocator = new PortAllocator();
        int base = allocator.allocateAny();
        allocator.release(base);

        assertEquals(base, allocator.claimOrZero(base));
        int fromRange = allocator.allocate(base, base + 20);
        assertNotEquals(base, fromRange, "range allocation must not reissue a claimed port");
        assertTrue(fromRange > base && fromRange <= base + 20);
    }
}
