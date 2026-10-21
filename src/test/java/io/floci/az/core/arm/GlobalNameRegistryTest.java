package io.floci.az.core.arm;

import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GlobalNameRegistry: one owner per name in each cloud")
class GlobalNameRegistryTest {

    private static final CloudProfile PUBLIC = CloudCatalog.forNameOrPublic("AzureCloud");
    private static final CloudProfile GOV = CloudCatalog.forNameOrPublic("AzureUSGovernment");

    private final List<GlobalNameSource.Claim> stored = new ArrayList<>();
    private final GlobalNameRegistry registry = new GlobalNameRegistry(() -> List.of(() -> List.copyOf(stored)),
        subscription -> subscription.startsWith("gov") ? GOV : PUBLIC);

    private static ArmResourceId cache(String sub, String rg, String name) {
        return ArmResourceId.parse("/subscriptions/" + sub + "/resourceGroups/" + rg
            + "/providers/Microsoft.Cache/Redis/" + name).orElseThrow();
    }

    @Test
    void theFirstResourceOwnsANameAndOthersFindItTaken() {
        ArmResourceId first = cache("s1", "rg", "c1");
        ArmResourceId second = cache("s2", "rg", "c1");

        assertEquals(GlobalNameRegistry.Outcome.RESERVED,
            registry.reserve(PUBLIC, CloudSuffix.REDIS, "c1", first).outcome());
        assertEquals(GlobalNameRegistry.Outcome.OWNED,
            registry.reserve(PUBLIC, CloudSuffix.REDIS, "C1", cache("S1", "RG", "c1")).outcome());
        GlobalNameRegistry.Reservation taken = registry.reserve(PUBLIC, CloudSuffix.REDIS, "c1", second);
        assertEquals(GlobalNameRegistry.Outcome.TAKEN, taken.outcome());
        assertEquals(first, taken.owner());
    }

    @Test
    void aFailedCreateFreesTheNameAndASucceededOneKeepsIt() {
        ArmResourceId owner = cache("s1", "rg", "c1");
        registry.reserve(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        registry.releasePending(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        assertTrue(registry.owner(PUBLIC, CloudSuffix.REDIS, "c1").isEmpty());

        registry.reserve(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        registry.commit(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        registry.releasePending(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        assertEquals(owner, registry.owner(PUBLIC, CloudSuffix.REDIS, "c1").orElseThrow());

        registry.release(PUBLIC, CloudSuffix.REDIS, "c1", cache("s2", "rg", "c1"));
        assertTrue(registry.owner(PUBLIC, CloudSuffix.REDIS, "c1").isPresent(), "only the owner releases");
        registry.release(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        assertTrue(registry.owner(PUBLIC, CloudSuffix.REDIS, "c1").isEmpty());
    }

    @Test
    void eachCloudAndEachSuffixHasItsOwnNames() {
        registry.reserve(PUBLIC, CloudSuffix.REDIS, "c1", cache("s1", "rg", "c1"));

        assertEquals(GlobalNameRegistry.Outcome.RESERVED,
            registry.reserve(GOV, CloudSuffix.REDIS, "c1", cache("gov1", "rg", "c1")).outcome());
        assertEquals(GlobalNameRegistry.Outcome.RESERVED,
            registry.reserve(PUBLIC, CloudSuffix.ACR, "c1", cache("s2", "rg", "c1")).outcome());
    }

    @Test
    void theRegistryRebuildsFromTheStoresOnFirstUseAndAfterAReset() {
        stored.add(new GlobalNameSource.Claim(CloudSuffix.REDIS, "kept", cache("s1", "rg", "kept")));
        stored.add(new GlobalNameSource.Claim(CloudSuffix.REDIS, "govname", cache("gov1", "rg", "govname")));

        assertEquals(GlobalNameRegistry.Outcome.TAKEN,
            registry.reserve(PUBLIC, CloudSuffix.REDIS, "kept", cache("s2", "rg", "kept")).outcome());
        assertTrue(registry.owner(GOV, CloudSuffix.REDIS, "govname").isPresent());
        assertTrue(registry.owner(PUBLIC, CloudSuffix.REDIS, "govname").isEmpty());

        stored.clear();
        registry.clear();
        assertTrue(registry.owner(PUBLIC, CloudSuffix.REDIS, "kept").isEmpty());
    }
}
