package io.floci.az.services.managementgroups;

import io.floci.az.core.StoredObject;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.DirectorySeed;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.services.managementgroups.ManagementGroupStore.Group;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Management groups: groups and placements written through to the store survive a restart")
class ManagementGroupStorePersistenceTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String OTHER_TENANT = "aaaaaaaa-0000-0000-0000-0000000000d9";
    private static final String SUB = "00000000-0000-0000-0000-000000000001";

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
        first.clear();
        assertTrue(backend.keys().isEmpty());

        ManagementGroupStore restarted = new ManagementGroupStore(directory, backend);
        assertEquals(List.of(TENANT), names(restarted.list(TENANT)));
        assertFalse(restarted.isPlaced(TENANT, SUB));
    }
}
