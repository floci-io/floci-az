package io.floci.az.services.managementgroups;

import io.floci.az.services.managementgroups.ManagementGroupStore.Group;
import io.floci.az.services.managementgroups.ManagementGroupStore.Snapshot;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Management groups: a snapshot is one consistent tree, unmoved by later changes")
class ManagementGroupStoreSnapshotTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String SUB = "aaaaaaaa-5555-5555-5555-000000000001";

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
}
