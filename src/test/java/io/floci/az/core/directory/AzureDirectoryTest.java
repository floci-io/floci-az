package io.floci.az.core.directory;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static io.floci.az.core.directory.DirectoryTestConfigs.subscription;
import static io.floci.az.core.directory.DirectoryTestConfigs.tenant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AzureDirectory: lookups, first use, reset and persistence")
class AzureDirectoryTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String SUB = "00000000-0000-0000-0000-000000000001";

    @TempDir
    Path tempDir;

    private static DirectorySeed seed() {
        return DirectorySeed.of("AzureCloud", TENANT, SUB, List.of(tenant("gov", "AzureUSGovernment")),
            List.of(subscription("gov-sub", "gov", null), subscription("off-sub", null, "Disabled")));
    }

    private static List<String> ids(List<Subscription> subscriptions) {
        return subscriptions.stream().map(Subscription::id).toList();
    }

    @Test
    void subscriptionsResolveToTheirTenantAndTenantsToTheirCloud() {
        AzureDirectory directory = new AzureDirectory(seed(), new InMemoryStorage<>());

        assertEquals("gov", directory.tenantOfSubscription("GOV-SUB"));
        assertEquals("AzureUSGovernment", directory.cloudOfTenant("gov").name());
        assertEquals(TENANT, directory.tenantOfSubscription("never-seen"), "an unknown subscription is the default tenant's");
        assertEquals("AzureCloud", directory.cloudOfTenant("never-seen").name(), "an unknown tenant is in the default cloud");
        assertEquals(List.of(SUB, "off-sub"), ids(directory.subscriptionsOfTenant(TENANT)));
        assertEquals(List.of("gov-sub"), ids(directory.subscriptionsOfTenant("GOV")));
    }

    @Test
    void firstUseRegistersOnceEnabledAndAfterTheConfiguredEntries() {
        AzureDirectory directory = new AzureDirectory(seed(), new InMemoryStorage<>());

        Subscription registered = directory.registerOnFirstUse("new-sub", null);
        Subscription again = directory.registerOnFirstUse("NEW-SUB", "gov");

        assertSame(registered, again);
        assertEquals(new Subscription("new-sub", TENANT, "floci-az local", SubscriptionState.ENABLED,
            DirectoryOrigin.FIRST_USE), registered);
        assertEquals(List.of(SUB, "gov-sub", "off-sub", "new-sub"), ids(directory.subscriptions()));
    }

    @Test
    void firstUseGoesUnderTheTokenTenantOnlyWhenTheDirectoryHoldsIt() {
        AzureDirectory directory = new AzureDirectory(seed(), new InMemoryStorage<>());

        assertEquals("gov", directory.registerOnFirstUse("token-sub", "GOV").tenantId());
        assertEquals(TENANT, directory.registerOnFirstUse("stranger-sub", "unknown-tenant").tenantId());
    }

    @Test
    void resetDropsFirstUseEntriesAndKeepsTheSeed() {
        AzureDirectory directory = new AzureDirectory(seed(), new InMemoryStorage<>());
        directory.registerOnFirstUse("new-sub", null);

        directory.clear();

        assertEquals(List.of(SUB, "gov-sub", "off-sub"), ids(directory.subscriptions()));
        assertEquals(2, directory.tenants().size());
    }

    @Test
    void aRestartKeepsFirstUseEntriesAndRewritesConfiguredOnesFromConfig() {
        StorageBackend<String, StoredObject> store = new InMemoryStorage<>();
        new AzureDirectory(seed(), store).registerOnFirstUse("new-sub", null);

        DirectorySeed edited = DirectorySeed.of("AzureCloud", TENANT, SUB, List.of(),
            List.of(subscription("off-sub", null, "Enabled")));
        AzureDirectory restarted = new AzureDirectory(edited, store);

        assertEquals(List.of(SUB, "off-sub", "new-sub"), ids(restarted.subscriptions()));
        assertEquals(SubscriptionState.ENABLED, restarted.subscription("off-sub").orElseThrow().state());
        assertEquals(Optional.empty(), restarted.tenant("gov"), "a tenant no longer configured is dropped");
        assertTrue(store.keys().stream().noneMatch(key -> key.contains("gov")), "stored keys: " + store.keys());
    }

    @Test
    void firstUseEntriesSurviveARestartThroughAPersistentStorageFactoryStore() {
        AzureDirectory before = new AzureDirectory(seed(), persistentFactory().create(AzureDirectory.STORE));
        before.registerOnFirstUse("persisted-sub", "gov");

        StorageFactory reopened = persistentFactory();
        AzureDirectory after = new AzureDirectory(seed(), reopened.create(AzureDirectory.STORE));

        assertEquals(new Subscription("persisted-sub", "gov", "floci-az local", SubscriptionState.ENABLED,
            DirectoryOrigin.FIRST_USE), after.subscription("persisted-sub").orElseThrow());
        assertEquals(List.of(SUB, "gov-sub", "off-sub", "persisted-sub"), ids(after.subscriptions()));
        assertTrue(tempDir.resolve(AzureDirectory.STORE + ".json").toFile().isFile());
    }

    /** A factory whose {@code directory} store is persistent under the temp dir, as {@code storage.services.directory.mode} sets it. */
    private StorageFactory persistentFactory() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());
        when(config.storage().mode()).thenReturn("memory");
        when(config.storage().services().directory().mode()).thenReturn(Optional.of("persistent"));
        return new StorageFactory(config);
    }
}
