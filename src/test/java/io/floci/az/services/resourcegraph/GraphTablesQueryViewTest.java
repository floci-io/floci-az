package io.floci.az.services.resourcegraph;

import io.floci.az.services.managementgroups.ManagementGroupStore;
import io.floci.az.services.resourcegraph.kql.KqlEngine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Resource Graph: one query reads each table once, whatever changes while it runs")
class GraphTablesQueryViewTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";

    @Inject
    GraphTables tables;

    @Inject
    ManagementGroupStore managementGroups;

    private static boolean hasGroup(List<Map<String, Object>> rows, String name) {
        return rows.stream().anyMatch(row -> "microsoft.management/managementgroups".equals(row.get("type"))
            && name.equals(row.get("name")));
    }

    @Test
    void everyReadOfOneQuerySeesTheSameHierarchy() {
        managementGroups.clear();
        managementGroups.createOrUpdate(TENANT, "before", "Before", null);
        KqlEngine.Tables query = tables.queryView();
        List<Map<String, Object>> first = query.rows("resourcecontainers");

        // A change landing between two reads of one query, as between a union's branches.
        managementGroups.createOrUpdate(TENANT, "during", "During", null);
        List<Map<String, Object>> second = query.rows("ResourceContainers");

        assertSame(first, second);
        assertTrue(hasGroup(second, "before"));
        assertFalse(hasGroup(second, "during"));
        // The next query sees it.
        assertTrue(hasGroup(tables.queryView().rows("resourcecontainers"), "during"));
        managementGroups.clear();
    }
}
