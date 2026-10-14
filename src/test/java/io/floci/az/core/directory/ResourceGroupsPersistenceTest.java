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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ResourceGroups: groups written through to the directory store survive a restart")
class ResourceGroupsPersistenceTest {

    private static final String SUB = "7a1d0000-0000-4000-8000-00000000c0a7";

    @TempDir
    Path tempDir;

    private static ResourceGroup tagged() {
        return new ResourceGroup(SUB, "Rg-Kept", "westeurope", Map.of("owner", "probe"), "manager-id",
            ResourceGroup.SUCCEEDED);
    }

    private static ResourceGroup retag(ResourceGroup group) {
        return new ResourceGroup(group.subscriptionId(), group.name(), group.location(), Map.of("owner", "patched"),
            group.managedBy(), group.provisioningState());
    }

    @Test
    void aTaggedGroupIsReadBackInAnyCasingAfterARestart() {
        StorageBackend<String, StoredObject> store = new InMemoryStorage<>();
        new ResourceGroups(store).put(tagged());

        ResourceGroups restarted = new ResourceGroups(store);

        assertEquals(tagged(), restarted.get(SUB, "Rg-Kept").orElseThrow());
        assertEquals(tagged(), restarted.get(SUB.toUpperCase(), "RG-KEPT").orElseThrow());
        assertTrue(restarted.exists(SUB, "rg-kept"));
        assertEquals(List.of(tagged()), restarted.list(SUB));
        assertEquals("/subscriptions/" + SUB + "/resourceGroups/Rg-Kept",
            restarted.get(SUB, "rg-kept").orElseThrow().id());
    }

    @Test
    void aPatchAndADeleteSurviveAnotherRestart() {
        StorageBackend<String, StoredObject> store = new InMemoryStorage<>();
        ResourceGroups first = new ResourceGroups(store);
        first.put(tagged());
        first.createIfAbsent(SUB, "rg-implicit", "northeurope");

        ResourceGroups second = new ResourceGroups(store);
        assertTrue(second.update(SUB, "RG-KEPT", ResourceGroupsPersistenceTest::retag).isPresent());
        assertTrue(second.remove(SUB, "RG-IMPLICIT").isPresent());

        ResourceGroups third = new ResourceGroups(store);
        ResourceGroup kept = third.get(SUB, "rg-kept").orElseThrow();
        assertEquals(Map.of("owner", "patched"), kept.tags());
        assertEquals("Rg-Kept", kept.name());
        assertEquals("westeurope", kept.location());
        assertEquals("manager-id", kept.managedBy());
        assertFalse(third.exists(SUB, "rg-implicit"));
        assertEquals(List.of(kept), third.list(SUB));
    }

    @Test
    void aResetLeavesNothingToReload() {
        StorageBackend<String, StoredObject> store = new InMemoryStorage<>();
        ResourceGroups groups = new ResourceGroups(store);
        groups.put(tagged());

        groups.clear();

        assertEquals(List.of(), new ResourceGroups(store).list(SUB));
    }

    @Test
    void groupsSurviveARestartThroughAPersistentStorageFactoryStore() {
        ResourceGroups before = new ResourceGroups(persistentFactory().create(AzureDirectory.STORE));
        before.put(tagged());
        before.put(new ResourceGroup(SUB, "rg-gone", "eastus2", null, null, ResourceGroup.SUCCEEDED));

        ResourceGroups reopened = new ResourceGroups(persistentFactory().create(AzureDirectory.STORE));
        assertEquals(tagged(), reopened.get(SUB, "RG-KEPT").orElseThrow());
        assertNull(reopened.get(SUB, "rg-gone").orElseThrow().tags());
        reopened.update(SUB, "rg-kept", ResourceGroupsPersistenceTest::retag);
        reopened.remove(SUB, "Rg-Gone");

        ResourceGroups after = new ResourceGroups(persistentFactory().create(AzureDirectory.STORE));
        assertEquals(List.of(retag(tagged())), after.list(SUB));
        assertTrue(tempDir.resolve(AzureDirectory.STORE + ".json").toFile().isFile());
    }

    @Test
    void strictResourceGroupsAlonePersistsGroups() {
        configured(false, true).put(tagged());

        assertEquals(tagged(), configured(false, true).get(SUB, "rg-kept").orElseThrow());
    }

    @Test
    void resourceGroupSemanticsAlonePersistsGroups() {
        configured(true, false).put(tagged());

        assertEquals(tagged(), configured(true, false).get(SUB, "rg-kept").orElseThrow());
    }

    @Test
    void groupsStayInMemoryWhenNeitherSwitchIsOn() {
        configured(false, false).put(tagged());

        assertFalse(configured(false, false).exists(SUB, "rg-kept"));
        assertFalse(tempDir.resolve(AzureDirectory.STORE + ".json").toFile().exists());
    }

    /** Groups built the way CDI builds them, from the two switches that turn persistence on. */
    private ResourceGroups configured(boolean resourceGroupSemantics, boolean strictResourceGroups) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.resourceGroupSemantics()).thenReturn(resourceGroupSemantics);
        when(config.strictResourceGroups()).thenReturn(strictResourceGroups);
        return new ResourceGroups(config, persistentFactory());
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
