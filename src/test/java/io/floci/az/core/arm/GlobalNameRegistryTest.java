package io.floci.az.core.arm;

import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GlobalNameRegistry: one owner per name in each cloud")
class GlobalNameRegistryTest {

    private static final CloudProfile PUBLIC = CloudCatalog.forNameOrPublic("AzureCloud");
    private static final CloudProfile GOV = CloudCatalog.forNameOrPublic("AzureUSGovernment");

    private final List<GlobalNameSource.Claim> stored = new ArrayList<>();
    private final GlobalNameRegistry registry = new GlobalNameRegistry(() -> List.of(() -> List.copyOf(stored)),
        subscription -> subscription.startsWith("gov") ? GOV : PUBLIC, () -> Set.of(CloudSuffix.REDIS));

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
        long failed = registry.reserve(PUBLIC, CloudSuffix.REDIS, "c1", owner).token();
        registry.releasePending(PUBLIC, CloudSuffix.REDIS, "c1", failed);
        assertTrue(registry.owner(PUBLIC, CloudSuffix.REDIS, "c1").isEmpty());

        long succeeded = registry.reserve(PUBLIC, CloudSuffix.REDIS, "c1", owner).token();
        registry.commit(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        registry.releasePending(PUBLIC, CloudSuffix.REDIS, "c1", succeeded);
        assertEquals(owner, registry.owner(PUBLIC, CloudSuffix.REDIS, "c1").orElseThrow());

        registry.release(PUBLIC, CloudSuffix.REDIS, "c1", cache("s2", "rg", "c1"));
        assertTrue(registry.owner(PUBLIC, CloudSuffix.REDIS, "c1").isPresent(), "only the owner releases");
        registry.release(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        assertTrue(registry.owner(PUBLIC, CloudSuffix.REDIS, "c1").isEmpty());
    }

    @Test
    void aConcurrentPutToTheSameResourceCannotFreeTheNameTheFirstIsCreating() {
        ArmResourceId owner = cache("s1", "rg", "c1");
        GlobalNameRegistry.Reservation first = registry.reserve(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        GlobalNameRegistry.Reservation second = registry.reserve(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        assertEquals(GlobalNameRegistry.Outcome.OWNED, second.outcome());

        registry.releasePending(PUBLIC, CloudSuffix.REDIS, "c1", second.token());

        assertEquals(GlobalNameRegistry.Outcome.TAKEN,
            registry.reserve(PUBLIC, CloudSuffix.REDIS, "c1", cache("s2", "rg", "c1")).outcome(),
            "the second PUT failing leaves the first one's reservation");
        registry.commit(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        assertEquals(owner, registry.owner(PUBLIC, CloudSuffix.REDIS, "c1").orElseThrow());
        registry.releasePending(PUBLIC, CloudSuffix.REDIS, "c1", first.token());
        assertTrue(registry.owner(PUBLIC, CloudSuffix.REDIS, "c1").isPresent(), "a committed name stays held");
    }

    @Test
    void aCreateThatSucceedsAfterADeleteReleasedItsNameHoldsItOnlyIfTheResourceIsStored() {
        ArmResourceId owner = cache("s1", "rg", "c1");
        registry.reserve(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        registry.release(PUBLIC, CloudSuffix.REDIS, "c1", owner);

        registry.commit(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        assertTrue(registry.owner(PUBLIC, CloudSuffix.REDIS, "c1").isEmpty(), "the delete won: nothing is stored");

        stored.add(new GlobalNameSource.Claim(CloudSuffix.REDIS, "c1", owner));
        registry.commit(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        assertEquals(owner, registry.owner(PUBLIC, CloudSuffix.REDIS, "c1").orElseThrow(),
            "the PUT stored the resource after the delete, so it holds the name again");
    }

    @Test
    void aDeleteThatFinishesAfterAPutRecreatedTheResourceKeepsTheName() {
        ArmResourceId owner = cache("s1", "rg", "c1");
        GlobalNameSource.Claim claim = new GlobalNameSource.Claim(CloudSuffix.REDIS, "c1", owner);
        registry.reserve(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        stored.add(claim);
        registry.commit(PUBLIC, CloudSuffix.REDIS, "c1", owner);

        stored.remove(claim);
        assertEquals(GlobalNameRegistry.Outcome.OWNED,
            registry.reserve(PUBLIC, CloudSuffix.REDIS, "c1", owner).outcome(),
            "a PUT to the same cache while the DELETE is in flight finds the name owned");
        stored.add(claim);
        registry.commit(PUBLIC, CloudSuffix.REDIS, "c1", owner);
        registry.release(PUBLIC, CloudSuffix.REDIS, "c1", owner);

        assertEquals(owner, registry.owner(PUBLIC, CloudSuffix.REDIS, "c1").orElseThrow(),
            "the PUT stored the cache again, so the DELETE's release keeps the name");
        assertEquals(GlobalNameRegistry.Outcome.TAKEN,
            registry.reserve(PUBLIC, CloudSuffix.REDIS, "c1", cache("s2", "rg", "c1")).outcome());
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
    void aNameIsOneNameAcrossCloudsUnlessItsTypeIsPerCloud() {
        registry.reserve(PUBLIC, CloudSuffix.POSTGRES, "db1", cache("s1", "rg", "db1"));

        assertEquals(GlobalNameRegistry.Outcome.TAKEN,
            registry.reserve(GOV, CloudSuffix.POSTGRES, "db1", cache("gov1", "rg", "db1")).outcome(),
            "a database container is keyed by the server name, so the name is one across clouds");
        registry.reserve(PUBLIC, CloudSuffix.REDIS, "c9", cache("s1", "rg", "c9"));
        assertEquals(GlobalNameRegistry.Outcome.RESERVED,
            registry.reserve(GOV, CloudSuffix.REDIS, "c9", cache("gov1", "rg", "c9")).outcome(),
            "Redis declares its name per cloud");
    }

    @Test
    void aHostRoutedNameIsOneNameAcrossClouds() {
        ArmResourceId publicOwner = cache("s1", "rg", "shared");
        registry.reserve(PUBLIC, CloudSuffix.ACR, "shared", publicOwner);

        GlobalNameRegistry.Reservation gov = registry.reserve(GOV, CloudSuffix.ACR, "shared", cache("gov1", "rg", "shared"));
        assertEquals(GlobalNameRegistry.Outcome.TAKEN, gov.outcome());
        assertEquals(publicOwner, gov.owner());
        assertEquals(publicOwner, registry.owner(GOV, CloudSuffix.ACR, "shared").orElseThrow());
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
