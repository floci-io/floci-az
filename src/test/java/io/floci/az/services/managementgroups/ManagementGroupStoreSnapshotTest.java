package io.floci.az.services.managementgroups;

import io.floci.az.services.managementgroups.ManagementGroupStore.Group;
import io.floci.az.services.managementgroups.ManagementGroupStore.Snapshot;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Management groups: a snapshot is one consistent tree, unmoved by later changes")
class ManagementGroupStoreSnapshotTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String SUB = "aaaaaaaa-5555-5555-5555-000000000001";
    private static final String UNPLACED_SUB = "aaaaaaaa-5555-5555-5555-000000000002";

    @Inject
    ManagementGroupStore store;

    @Test
    void aSnapshotKeepsTheTreeItWasTakenFrom() {
        store.clear();
        store.createOrUpdate(TENANT, "platform", "Platform", null);
        store.createOrUpdate(TENANT, "corp", "Corp", "platform");
        store.place(TENANT, SUB, "corp");
        Snapshot before = store.snapshot(TENANT);

        // Later moves change the store, not the snapshot: parent, ancestors and placement still agree with each other.
        store.createOrUpdate(TENANT, "landing", "Landing", null);
        store.move(TENANT, "corp", null, "landing");
        store.place(TENANT, SUB, "platform");

        assertEquals("platform", before.get("corp").orElseThrow().parent());
        assertEquals(List.of("platform", TENANT), before.ancestors("corp").stream().map(Group::name).toList());
        assertEquals("corp", before.parentOf(SUB).name());
        assertEquals(List.of(TENANT, "platform", "corp"), before.list().stream().map(Group::name).toList());
        assertTrue(before.get("landing").isEmpty());

        Snapshot after = store.snapshot(TENANT);
        assertEquals("landing", after.get("corp").orElseThrow().parent());
        assertEquals(List.of("landing", TENANT), after.ancestors("corp").stream().map(Group::name).toList());
        assertEquals("platform", after.parentOf(SUB).name());
        assertEquals(TENANT, after.root().name());
    }

    @Test
    void theStoreAndItsSnapshotWalkTheTreeTheSameWay() {
        store.clear();
        store.createOrUpdate(TENANT, "platform", "Platform", null);
        store.createOrUpdate(TENANT, "corp", "Corp", "platform");
        store.place(TENANT, SUB, "corp");
        Snapshot snapshot = store.snapshot(TENANT);

        for (String group : List.of(TENANT, "platform", "corp", "CORP", "missing")) {
            assertEquals(store.ancestors(TENANT, group), snapshot.ancestors(group), group);
        }
        assertEquals(List.of("platform", TENANT), snapshot.ancestors("corp").stream().map(Group::name).toList());
        assertEquals(store.parentOf(TENANT, SUB), snapshot.parentOf(SUB));
        // A subscription that was never placed sits under the tenant root in both.
        assertEquals(TENANT, snapshot.parentOf(UNPLACED_SUB).name());
        assertEquals(store.parentOf(TENANT, UNPLACED_SUB), snapshot.parentOf(UNPLACED_SUB));
    }

    @Test
    void aParentCycleEndsTheWalk() {
        // No write path stores a cycle; the walk still has to end if one is ever read back.
        Map<String, Group> cyclic = new LinkedHashMap<>();
        cyclic.put("a", new Group("a", "A", TENANT, "b", 1, Instant.EPOCH, null));
        cyclic.put("b", new Group("b", "B", TENANT, "a", 1, Instant.EPOCH, null));
        Snapshot snapshot = new Snapshot(TENANT, cyclic, Map.of());

        List<Group> walk = snapshot.ancestors("a");
        assertEquals("b", walk.get(0).name());
        assertTrue(walk.size() <= cyclic.size() + 1, "the walk is bounded by the number of groups");
    }
}
