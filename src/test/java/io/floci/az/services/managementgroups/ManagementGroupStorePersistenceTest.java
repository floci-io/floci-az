package io.floci.az.services.managementgroups;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.DirectorySeed;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.managementgroups.ManagementGroupStore.Group;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Management groups: groups and placements written through to the directory store survive a restart")
class ManagementGroupStorePersistenceTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String OTHER_TENANT = "aaaaaaaa-0000-0000-0000-0000000000d9";
    private static final String SUB = "00000000-0000-0000-0000-000000000001";
    private static final String FIRST_USE_SUB = "7a1d0000-0000-4000-8000-00000000c0a7";

    @TempDir
    Path tempDir;

    private final AzureDirectory directory = new AzureDirectory(
        DirectorySeed.of("AzureCloud", TENANT, SUB, List.of(), List.of()), new InMemoryStorage<>());

    private static List<String> names(List<Group> groups) {
        return groups.stream().map(Group::name).toList();
    }

    @Test
    void theHierarchyIsReadBackAsItWasWritten() {
        StorageBackend<String, StoredObject> backend = new InMemoryStorage<>();
        ManagementGroupStore first = new ManagementGroupStore(directory, backend);
        first.createOrUpdate(TENANT, "Zeta", "Zeta first", null);
        first.createOrUpdate(TENANT, "alpha", "Alpha second", "zeta");
        first.createOrUpdate(TENANT, "landing", "Landing", null);
        first.move(TENANT, "alpha", "Alpha moved", "landing");
        first.place(TENANT, SUB, "ALPHA");
        Group root = first.root(TENANT);
        Group alpha = first.get(TENANT, "alpha").orElseThrow();

        ManagementGroupStore restarted = new ManagementGroupStore(directory, backend);
        // Creation order, the root first, whatever the names sort to.
        assertEquals(List.of(TENANT, "Zeta", "alpha", "landing"), names(restarted.list(TENANT)));
        assertEquals(root, restarted.root(TENANT));
        assertEquals(alpha, restarted.get(TENANT, "ALPHA").orElseThrow());
        assertEquals("landing", restarted.get(TENANT, "alpha").orElseThrow().parent());
        assertEquals(2, restarted.get(TENANT, "alpha").orElseThrow().version());
        assertEquals("alpha", restarted.parentOf(TENANT, SUB).name());
        assertTrue(restarted.isPlaced(TENANT, SUB));
        assertEquals(List.of("landing", TENANT), names(restarted.ancestors(TENANT, "alpha")));

        // A group created after the restart still lists after the earlier ones.
        restarted.createOrUpdate(TENANT, "beta", "Beta", null);
        assertEquals(List.of(TENANT, "Zeta", "alpha", "landing", "beta"),
            names(new ManagementGroupStore(directory, backend).list(TENANT)));
    }

    @Test
    void deletesAndRemovedPlacementsSurviveAnotherRestart() {
        StorageBackend<String, StoredObject> backend = new InMemoryStorage<>();
        ManagementGroupStore first = new ManagementGroupStore(directory, backend);
        first.createOrUpdate(TENANT, "prod", "Production", null);
        first.createOrUpdate(TENANT, "gone", "Gone", null);
        first.place(TENANT, SUB, "prod");

        ManagementGroupStore second = new ManagementGroupStore(directory, backend);
        assertTrue(second.delete(TENANT, "gone"));
        assertFalse(second.unplace(TENANT, SUB, "gone"));
        assertTrue(second.unplace(TENANT, SUB, "prod"));

        ManagementGroupStore third = new ManagementGroupStore(directory, backend);
        assertEquals(List.of(TENANT, "prod"), names(third.list(TENANT)));
        assertFalse(third.isPlaced(TENANT, SUB));
        assertEquals(TENANT, third.parentOf(TENANT, SUB).name());
    }

    @Test
    void tenantsKeepTheirOwnGroupsOfOneName() {
        StorageBackend<String, StoredObject> backend = new InMemoryStorage<>();
        ManagementGroupStore first = new ManagementGroupStore(directory, backend);
        first.createOrUpdate(TENANT, "prod", "Home production", null);
        first.createOrUpdate(OTHER_TENANT, "prod", "Team production", null);

        ManagementGroupStore restarted = new ManagementGroupStore(directory, backend);
        assertEquals("Home production", restarted.get(TENANT, "prod").orElseThrow().displayName());
        assertEquals("Team production", restarted.get(OTHER_TENANT, "prod").orElseThrow().displayName());
        assertEquals(OTHER_TENANT, restarted.get(OTHER_TENANT, "prod").orElseThrow().parent());
    }

    @Test
    void clearEmptiesTheStoreToo() {
        StorageBackend<String, StoredObject> backend = new InMemoryStorage<>();
        ManagementGroupStore first = new ManagementGroupStore(directory, backend);
        first.createOrUpdate(TENANT, "prod", "Production", null);
        first.place(TENANT, SUB, "prod");
        // The store is the directory's: another owner's entry is not this store's to clear.
        put(backend, "subscription/other", "{}");
        first.clear();
        assertEquals(List.of("subscription/other"), List.copyOf(backend.keys()));

        ManagementGroupStore restarted = new ManagementGroupStore(directory, backend);
        assertEquals(List.of(TENANT), names(restarted.list(TENANT)));
        assertFalse(restarted.isPlaced(TENANT, SUB));
    }

    @Test
    void theDirectoryAndTheHierarchyShareOneStore() {
        StorageBackend<String, StoredObject> backend = new InMemoryStorage<>();
        DirectorySeed seed = DirectorySeed.of("AzureCloud", TENANT, SUB, List.of(), List.of());
        ManagementGroupStore first = new ManagementGroupStore(new AzureDirectory(seed, backend), backend);
        first.createOrUpdate(TENANT, "prod", "Production", null);
        first.place(TENANT, FIRST_USE_SUB, "prod");

        // The directory rewrites its own entries at every start and leaves the hierarchy's alone.
        AzureDirectory restartedDirectory = new AzureDirectory(seed, backend);
        ManagementGroupStore restarted = new ManagementGroupStore(restartedDirectory, backend);
        assertTrue(restartedDirectory.subscription(FIRST_USE_SUB).isPresent());
        assertEquals("prod", restarted.parentOf(TENANT, FIRST_USE_SUB).name());
        assertEquals(List.of(FIRST_USE_SUB), restarted.childSubscriptions(TENANT, "prod").stream().map(s -> s.id()).toList());
    }

    @Test
    void theHierarchyFollowsTheDirectoryStorageMode() {
        // Global mode memory, the directory store persistent: the hierarchy is written to the directory's file.
        ManagementGroupStore first = new ManagementGroupStore(directory, directoryPersistentFactory());
        first.createOrUpdate(TENANT, "prod", "Production", null);
        first.place(TENANT, SUB, "prod");
        assertTrue(tempDir.resolve(AzureDirectory.STORE + ".json").toFile().exists());

        ManagementGroupStore restarted = new ManagementGroupStore(directory, directoryPersistentFactory());
        assertEquals(List.of(TENANT, "prod"), names(restarted.list(TENANT)));
        assertEquals("prod", restarted.parentOf(TENANT, SUB).name());
    }

    @Test
    void anEntryThatCannotBeUsedIsSkippedAndTheRestStillLoad() {
        StorageBackend<String, StoredObject> backend = new InMemoryStorage<>();
        ManagementGroupStore first = new ManagementGroupStore(directory, backend);
        first.createOrUpdate(TENANT, "prod", "Production", null);
        first.createOrUpdate(TENANT, "broken", "Broken", null);
        first.createOrUpdate(TENANT, "child", "Child of broken", "broken");
        first.createOrUpdate(OTHER_TENANT, "team", "Team", null);
        first.place(TENANT, SUB, "prod");

        String groups = "managementgroup/" + TENANT + "/";
        put(backend, groups + "broken", group("broken", TENANT, TENANT, "not-a-timestamp"));
        put(backend, groups + "nameless", "{\"tenantId\": \"" + TENANT + "\", \"parent\": \"" + TENANT + "\", \"sequence\": 90}");
        put(backend, groups + "tenantless", "{\"name\": \"tenantless\", \"parent\": \"" + TENANT + "\", \"sequence\": 91}");
        put(backend, groups + "rootless", group("rootless", TENANT, null, Instant.now().toString()));
        put(backend, groups + "garbled", "not json");
        put(backend, "managementgroupplacement/" + TENANT + "/" + FIRST_USE_SUB, "{\"tenantId\": \"" + TENANT + "\"}");

        ManagementGroupStore restarted = new ManagementGroupStore(directory, backend);
        // Every usable group is back; the one whose parent was skipped hangs off the root.
        assertEquals(List.of(TENANT, "prod", "child"), names(restarted.list(TENANT)));
        assertEquals(TENANT, restarted.get(TENANT, "child").orElseThrow().parent());
        assertEquals(List.of("prod", "child"), names(restarted.childGroups(TENANT, TENANT)));
        assertEquals("prod", restarted.parentOf(TENANT, SUB).name());
        assertFalse(restarted.isPlaced(TENANT, FIRST_USE_SUB));
        assertEquals(List.of(OTHER_TENANT, "team"), names(restarted.list(OTHER_TENANT)));
        // The move under the root was written back, so the next start reads it without repairing again.
        assertEquals(TENANT, new ManagementGroupStore(directory, backend).get(TENANT, "child").orElseThrow().parent());
    }

    private static String group(String name, String tenantId, String parent, String updatedTime) {
        return "{\"name\": \"" + name + "\", \"displayName\": \"" + name + "\", \"tenantId\": \"" + tenantId + "\", \"parent\": "
            + (parent == null ? "null" : "\"" + parent + "\"") + ", \"version\": 1, \"updatedTime\": \"" + updatedTime
            + "\", \"sequence\": 99}";
    }

    private static void put(StorageBackend<String, StoredObject> backend, String key, String json) {
        backend.put(key, new StoredObject(key, json.getBytes(StandardCharsets.UTF_8), Map.of(), Instant.now(), "etag"));
    }

    /** A factory whose {@code directory} store is persistent under the temp dir while the global mode is memory. */
    private StorageFactory directoryPersistentFactory() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());
        when(config.storage().mode()).thenReturn("memory");
        when(config.storage().services().directory().mode()).thenReturn(Optional.of("persistent"));
        return new StorageFactory(config);
    }
}
