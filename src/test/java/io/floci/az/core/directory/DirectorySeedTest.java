package io.floci.az.core.directory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.floci.az.core.directory.DirectoryTestConfigs.subscription;
import static io.floci.az.core.directory.DirectoryTestConfigs.tenant;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("DirectorySeed: the default and configured tenants and subscriptions")
class DirectorySeedTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String SUB = "00000000-0000-0000-0000-000000000001";

    @Test
    void withNothingConfiguredTheSeedIsTheDefaultTenantAndSubscription() {
        DirectorySeed seed = DirectorySeed.of("AzureCloud", TENANT, SUB, List.of(), List.of());

        assertEquals(List.of(new Tenant(TENANT, "AzureCloud", "floci-az local", "floci-az.local", DirectoryOrigin.SEED)),
            seed.tenants());
        assertEquals(List.of(new Subscription(SUB, TENANT, "floci-az local", SubscriptionState.ENABLED,
            DirectoryOrigin.SEED)), seed.subscriptions());
        assertEquals("AzureCloud", seed.defaultCloud().name());
    }

    @Test
    void configuredEntriesFollowTheDefaultsInConfigOrder() {
        DirectorySeed seed = DirectorySeed.of("azurecloud", TENANT, SUB,
            List.of(tenant("gov-tenant", "AzureUSGovernment"), tenant("plain-tenant", null)),
            List.of(subscription("sub-b", "gov-tenant", "Disabled"), subscription("sub-a", null, null)));

        assertEquals(List.of(TENANT, "gov-tenant", "plain-tenant"), seed.tenants().stream().map(Tenant::id).toList());
        assertEquals("AzureUSGovernment", seed.tenants().get(1).cloud());
        assertEquals("AzureCloud", seed.tenants().get(2).cloud(), "a tenant naming no cloud is in the default cloud");
        assertEquals(List.of(SUB, "sub-b", "sub-a"), seed.subscriptions().stream().map(Subscription::id).toList());
        assertEquals(new Subscription("sub-b", "gov-tenant", "floci-az local", SubscriptionState.DISABLED,
            DirectoryOrigin.CONFIG), seed.subscriptions().get(1));
        assertEquals(TENANT, seed.subscriptions().get(2).tenantId());
    }

    @Test
    void anEntryWithADefaultIdOverridesTheDefaultInPlace() {
        DirectorySeed seed = DirectorySeed.of("AzureCloud", TENANT, SUB,
            List.of(tenant(TENANT.toUpperCase(), "AzureChinaCloud")), List.of(subscription(SUB, null, "Warned")));

        assertEquals(1, seed.tenants().size());
        assertEquals("AzureChinaCloud", seed.tenants().getFirst().cloud());
        assertEquals(DirectoryOrigin.CONFIG, seed.tenants().getFirst().origin());
        assertEquals(1, seed.subscriptions().size());
        assertEquals(SubscriptionState.WARNED, seed.subscriptions().getFirst().state());
    }

    @Test
    void anUnknownDefaultCloudFailsWithTheKnownNames() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> DirectorySeed.of("AzureGermanCloud", TENANT, SUB, List.of(), List.of()));

        assertThat(failure.getMessage(), containsString("floci-az.cloud.default"));
        assertThat(failure.getMessage(), containsString("AzureUSGovernment"));
    }

    @Test
    void anUnknownTenantCloudFails() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> DirectorySeed.of("AzureCloud", TENANT, SUB, List.of(tenant("t", "Mars")), List.of()));

        assertThat(failure.getMessage(), containsString("floci-az.tenants[0].cloud"));
    }

    @Test
    void aSubscriptionUnderAnUnlistedTenantFails() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> DirectorySeed.of("AzureCloud", TENANT, SUB, List.of(), List.of(subscription("s", "nobody", null))));

        assertThat(failure.getMessage(), containsString("floci-az.subscriptions[0].tenant-id"));
    }

    @Test
    void anUnknownStateFailsWithTheWireNames() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> DirectorySeed.of("AzureCloud", TENANT, SUB, List.of(), List.of(subscription("s", null, "Suspended"))));

        assertThat(failure.getMessage(), containsString("floci-az.subscriptions[0].state"));
        assertThat(failure.getMessage(), containsString("PastDue"));
    }

    @Test
    void aRepeatedIdFails() {
        assertThrows(IllegalStateException.class, () -> DirectorySeed.of("AzureCloud", TENANT, SUB,
            List.of(tenant("t", null), tenant("T", null)), List.of()));
        assertThrows(IllegalStateException.class, () -> DirectorySeed.of("AzureCloud", TENANT, SUB,
            List.of(), List.of(subscription("s", null, null), subscription("S", null, null))));
    }

    @Test
    void stateNamesAreTheWireSpelling() {
        assertEquals(List.of("Enabled", "Warned", "PastDue", "Disabled", "Deleted"),
            List.of(SubscriptionState.values()).stream().map(SubscriptionState::wireName).toList());
        assertEquals(SubscriptionState.PAST_DUE, SubscriptionState.fromWireName("pastdue"));
    }
}
