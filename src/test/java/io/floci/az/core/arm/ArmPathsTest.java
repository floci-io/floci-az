package io.floci.az.core.arm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ArmPaths — ARM management-plane path parsing")
class ArmPathsTest {

    private static final String PATH =
            "subscriptions/sub-1/resourceGroups/rg-1/providers/Microsoft.Cache/redis/cache1?api-version=2024-03-01";

    @Test
    void extractsSubscriptionAndResourceGroup() {
        assertEquals("sub-1", ArmPaths.subscription(PATH, "unknown"));
        assertEquals("rg-1", ArmPaths.resourceGroup(PATH, "unknown"));
    }

    @Test
    void resourceGroupSegmentIsCaseInsensitive() {
        assertEquals("rg-1", ArmPaths.resourceGroup("subscriptions/s/resourcegroups/rg-1/x", "unknown"));
        assertEquals("rg-1", ArmPaths.resourceGroup("subscriptions/s/ResourceGroups/rg-1/x", "unknown"));
    }

    @Test
    void subscriptionSegmentStaysCaseSensitive() {
        // Historical ArmHandler behavior: "Subscriptions" (capitalised) does not match.
        assertEquals("unknown", ArmPaths.subscription("Subscriptions/s/resourceGroups/rg/x", "unknown"));
    }

    @Test
    void fallbacksAreCallerSupplied() {
        assertEquals("unknown", ArmPaths.resourceGroup("subscriptions/s/providers/x", "unknown"));
        assertEquals("default", ArmPaths.resourceGroup("subscriptions/s/providers/x", "default"));
        assertEquals(Optional.empty(), ArmPaths.resourceGroup(null));
    }

    @Test
    void resourceNameCapturesSegmentAfterCollection() {
        assertEquals("cache1", ArmPaths.resourceName(PATH, "redis", "unknown"));
        assertEquals("unknown", ArmPaths.resourceName(PATH, "servers", "unknown"));
        // Query separator terminates the captured name.
        assertEquals("cache1", ArmPaths.resourceName("providers/Microsoft.Cache/redis/cache1?x=1", "redis", "unknown"));
    }

    @Test
    void afterSegmentReturnsRemainderWithoutQuery() {
        assertEquals("redis/cache1",
                ArmPaths.afterSegment(PATH, "/providers/Microsoft.Cache/", "unknown"));
        assertEquals("unknown", ArmPaths.afterSegment(PATH, "/providers/Microsoft.Sql/", "unknown"));
    }

    @Test
    void segmentAfterMatchesCaseInsensitively() {
        assertEquals("sub-1", ArmPaths.segmentAfter(PATH, "SUBSCRIPTIONS", "default"));
        assertEquals("default", ArmPaths.segmentAfter("no/match/here", "subscriptions", "default"));
        assertEquals("default", ArmPaths.segmentAfter(null, "subscriptions", "default"));
    }
}
