package io.floci.az.services.resourcegraph.kql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("KqlEngine: the query shapes Resource Graph clients send")
class KqlEngineTest {

    private static final String SUB_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String SUB_B = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String ASSIGNMENT = "/subscriptions/" + SUB_A + "/resourceGroups/rg1/providers/Microsoft.Authorization/policyAssignments/assign1";

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private static Map<String, Object> exemption(String name, String subscription, String assignmentId, String expiresOn) {
        Map<String, Object> properties = row("policyAssignmentId", assignmentId, "displayName", "Sample waiver",
                "exemptionCategory", "Waiver");
        if (expiresOn != null) {
            properties.put("expiresOn", expiresOn);
        }
        return row("id", "/subscriptions/" + subscription + "/resourceGroups/rg1/providers/Microsoft.Authorization/policyExemptions/" + name,
                "name", name, "type", "microsoft.authorization/policyexemptions", "subscriptionId", subscription,
                "resourceGroup", "rg1", "properties", properties);
    }

    private static KqlEngine.Tables tables() {
        List<Map<String, Object>> policy = new ArrayList<>();
        policy.add(exemption("w1", SUB_A, ASSIGNMENT, null));
        policy.add(exemption("w2", SUB_A, ASSIGNMENT.toUpperCase(), "2099-01-01T00:00:00Z"));
        policy.add(exemption("w3", SUB_B, ASSIGNMENT, "2000-01-01T00:00:00Z"));
        policy.add(exemption("other", SUB_B, "/subscriptions/x/providers/Microsoft.Authorization/policyAssignments/other", null));
        policy.add(row("id", ASSIGNMENT, "name", "assign1", "type", "microsoft.authorization/policyassignments",
                "subscriptionId", SUB_A, "properties", row("policyDefinitionId", "/d")));
        policy.add(row("id", "/subscriptions/" + SUB_A + "/providers/Microsoft.Authorization/policyDefinitions/def1",
                "name", "def1", "type", "microsoft.authorization/policydefinitions", "subscriptionId", SUB_A, "properties", row()));

        List<Map<String, Object>> containers = new ArrayList<>();
        containers.add(row("id", "/subscriptions/" + SUB_A, "name", "Sub A", "type", "microsoft.resources/subscriptions",
                "tenantId", "t1", "subscriptionId", SUB_A,
                "properties", row("managementGroupAncestorsChain", List.of(row("name", "mg-root", "displayName", "Root")))));
        containers.add(row("id", "/subscriptions/" + SUB_A + "/resourceGroups/rg1", "name", "rg1",
                "type", "microsoft.resources/subscriptions/resourcegroups", "tenantId", "t1", "subscriptionId", SUB_A,
                "location", "eastus", "properties", row()));

        List<Map<String, Object>> resources = new ArrayList<>();
        resources.add(row("id", "/subscriptions/" + SUB_A + "/resourceGroups/rg1/providers/Microsoft.Storage/storageAccounts/sa1",
                "name", "sa1", "type", "microsoft.storage/storageaccounts", "location", "eastus", "subscriptionId", SUB_A,
                "tags", row("env", "prod"), "properties", row("creationTime", "2026-01-02T03:04:05Z")));
        resources.add(row("id", "/subscriptions/" + SUB_A + "/resourceGroups/rg1/providers/Microsoft.Storage/storageAccounts/sa2",
                "name", "sa2", "type", "microsoft.storage/storageaccounts", "location", "westus", "subscriptionId", SUB_A,
                "tags", null, "properties", row()));
        resources.add(row("id", "/subscriptions/" + SUB_A + "/resourceGroups/rg1/providers/Microsoft.KeyVault/vaults/kv1",
                "name", "kv1", "type", "microsoft.keyvault/vaults", "location", "eastus", "subscriptionId", SUB_A,
                "properties", row()));

        List<Map<String, Object>> authorization = new ArrayList<>();
        authorization.add(row("id", "/subscriptions/" + SUB_A + "/providers/Microsoft.Authorization/roleAssignments/ra1",
                "type", "microsoft.authorization/roleassignments",
                "properties", row("principalId", "PRINCIPAL-1", "roleDefinitionId", "/subscriptions/" + SUB_A + "/providers/Microsoft.Authorization/roleDefinitions/RD1")));
        authorization.add(row("id", "/subscriptions/" + SUB_A + "/providers/Microsoft.Authorization/roleDefinitions/rd1",
                "type", "microsoft.authorization/roledefinitions", "properties", row("roleName", "Reader")));

        return table -> switch (table.toLowerCase()) {
            case "policyresources" -> policy;
            case "resourcecontainers" -> containers;
            case "resources" -> resources;
            case "authorizationresources" -> authorization;
            default -> null;
        };
    }

    private static List<Map<String, Object>> run(String query) {
        return KqlEngine.run(Kql.parse(query), tables());
    }

    @Test
    void listsExemptionsOfAnAssignmentIgnoringCase() {
        List<Map<String, Object>> rows = run("""
                policyresources
                | where type =~ "MICROSOFT.AUTHORIZATION/policyexemptions"
                | where properties.policyAssignmentId =~ "%s"
                | project id, name, expiry = todatetime(properties.expiresOn), label = tostring(properties.displayName)
                """.formatted(ASSIGNMENT.toUpperCase()));
        assertEquals(3, rows.size());
        assertEquals(List.of("id", "name", "expiry", "label"), new ArrayList<>(rows.getFirst().keySet()));
        assertEquals("w1", rows.get(0).get("name"));
        assertNull(rows.get(0).get("expiry"));
        assertEquals(Instant.parse("2099-01-01T00:00:00Z"), rows.get(1).get("expiry"));
        assertEquals("Sample waiver", rows.get(0).get("label"));
    }

    @Test
    void countsUnexpiredExemptionsPerSubscription() {
        List<Map<String, Object>> rows = run("""
                policyresources
                | where type =~ "microsoft.authorization/policyexemptions" and subscriptionId in ( "%s", "%s" )
                | extend expiry = todatetime(properties.expiresOn)
                | where isnull(expiry) or expiry > now()
                | summarize active = count() by subscriptionId
                | sort by subscriptionId asc
                """.formatted(SUB_A, SUB_B));
        assertEquals(2, rows.size());
        assertEquals(SUB_A, rows.get(0).get("subscriptionId"));
        assertEquals(2L, rows.get(0).get("active"));
        assertEquals(1L, rows.get(1).get("active"));

        List<Map<String, Object>> total = run("""
                policyresources
                | where type == "microsoft.authorization/policyexemptions" and properties.policyAssignmentId =~ "%s"
                | where isnull(todatetime(properties.expiresOn)) or todatetime(properties.expiresOn) > now()
                | count
                """.formatted(ASSIGNMENT));
        assertEquals(2L, total.getFirst().get("Count"));
    }

    @Test
    void buildsTheHierarchyWithUnionAndDynamicFunctions() {
        List<Map<String, Object>> rows = run("""
                resourcecontainers
                | where type in~ ('microsoft.resources/subscriptions', 'microsoft.resources/subscriptions/resourcegroups')
                | extend parent = iff(type == 'microsoft.resources/subscriptions',
                      strcat('/providers/Microsoft.Management/managementGroups/', tostring(parse_json(properties.managementGroupAncestorsChain)[0].name)),
                      strcat('/subscriptions/', subscriptionId))
                | union (
                    resourcecontainers
                    | where type == 'microsoft.management/managementgroups'
                    | extend parent = tostring(properties.details.parent.id))
                | project id, name, type, tenantId, parent
                | order by id asc
                """);
        assertEquals(2, rows.size());
        assertEquals("/subscriptions/" + SUB_A, rows.get(0).get("id"));
        assertEquals("/providers/Microsoft.Management/managementGroups/mg-root", rows.get(0).get("parent"));
        assertEquals("/subscriptions/" + SUB_A, rows.get(1).get("parent"));
        assertEquals("t1", rows.get(1).get("tenantId"));
        assertEquals(List.of("id", "name", "type", "tenantId", "parent"), new ArrayList<>(rows.get(1).keySet()));
    }

    @Test
    void joinsRoleAssignmentsToDefinitions() {
        List<Map<String, Object>> rows = run("""
                authorizationresources
                | where type == "microsoft.authorization/roleassignments"
                | project principal = tolower(tostring(properties.principalId)), role = tolower(tostring(properties.roleDefinitionId))
                | join kind=inner (
                    authorizationresources
                    | where type == "microsoft.authorization/roledefinitions"
                    | project role = tolower(id), roleName = tostring(properties.roleName)
                ) on role
                | where principal == "principal-1"
                | project roleName
                """);
        assertEquals(List.of(row("roleName", "Reader")), rows);
        List<Map<String, Object>> explicitKeys = run("""
                authorizationresources
                | where type == "microsoft.authorization/roleassignments"
                | join kind=inner (authorizationresources | where type == "microsoft.authorization/roledefinitions")
                  on $left.properties.roleDefinitionId == $right.id
                | count
                """);
        assertEquals(0L, explicitKeys.getFirst().get("Count"));
    }

    @Test
    void summarisesPolicyEntitiesPerScopeWithCaseAndSplit() {
        List<Map<String, Object>> rows = run("""
                policyresources
                | extend scope = tostring(split(tolower(id), "/providers/microsoft.authorization/")[0])
                | extend kindLabel = case(
                    type has "policyassignments", "assignment",
                    type has "policyexemptions", "exemption",
                    type has "policydefinitions", "definition",
                    "other")
                | where isnotempty(scope) and kindLabel != "other"
                | summarize n = count() by scope, kindLabel
                """);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            counts.put(r.get("scope") + "|" + r.get("kindLabel"), (Long) r.get("n"));
        }
        assertEquals(2L, counts.get("/subscriptions/" + SUB_A + "/resourcegroups/rg1|exemption"));
        assertEquals(1L, counts.get("/subscriptions/" + SUB_A + "/resourcegroups/rg1|assignment"));
        assertEquals(1L, counts.get("/subscriptions/" + SUB_A + "|definition"));
    }

    @Test
    void leftOuterJoinKeepsUnmatchedRowsAndCoalesceFillsThem() {
        List<Map<String, Object>> rows = run("""
                policyresources
                | where type == "microsoft.authorization/policyassignments"
                | project assignment = tolower(id)
                | join kind=leftouter (
                    policyresources
                    | where type == "microsoft.policyinsights/policystates"
                    | summarize compliant = count() by assignment = tolower(tostring(properties.policyAssignmentId))
                ) on assignment
                | project assignment, compliant = coalesce(compliant, 0)
                """);
        assertEquals(1, rows.size());
        assertEquals(ASSIGNMENT.toLowerCase(), rows.getFirst().get("assignment"));
        assertEquals(0L, rows.getFirst().get("compliant"));
    }

    @Test
    void inOperatorsAcceptDynamicArraysAndDistinctOrderAndTake() {
        List<Map<String, Object>> rows = run("""
                resources
                | where type in~ (dynamic(["Microsoft.Storage/storageAccounts"]))
                | where location !in ("northeurope")
                | project resourceId = tolower(id), location
                | distinct resourceId, location
                | order by resourceId asc
                | take 5
                """);
        assertEquals(2, rows.size());
        assertTrue(String.valueOf(rows.get(0).get("resourceId")).endsWith("/sa1"));
    }

    @Test
    void countsResourcesByTypeWithRenamedByColumn() {
        List<Map<String, Object>> rows = run("""
                Resources
                | summarize n = count() by kindOfThing = type
                | order by n desc
                """);
        assertEquals(2, rows.size());
        assertEquals("microsoft.storage/storageaccounts", rows.get(0).get("kindOfThing"));
        assertEquals(2L, rows.get(0).get("n"));
        assertEquals(List.of("kindOfThing", "n"), new ArrayList<>(rows.get(0).keySet()));
    }

    @Test
    void unnamedProjectionsKeepTheColumnNameAndReportTheJoinSuffix() {
        List<Map<String, Object>> rows = run("""
                resources
                | where name == "sa1"
                | extend created = coalesce(systemData.createdAt, properties.creationTime, properties.timeCreated)
                | project tolower(id), todatetime(created), tags.env, label = iff(type has "vaults", "vault", name)
                """);
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.getFirst();
        assertEquals(List.of("id", "created", "tags_env", "label"), new ArrayList<>(row.keySet()));
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), row.get("created"));
        assertEquals("prod", row.get("tags_env"));
        assertEquals("sa1", row.get("label"));

        List<Map<String, Object>> joined = run("""
                resources | project id, name
                | join kind=inner (resources | project id, name) on id
                """);
        assertEquals(3, joined.size());
        assertTrue(joined.getFirst().containsKey("id1"));
        assertTrue(joined.getFirst().containsKey("name1"));
    }

    @Test
    void comparisonSemanticsFollowKusto() {
        assertEquals(1, run("resources | where tags.env == \"prod\"").size());
        assertEquals(0, run("resources | where tags.env == \"PROD\"").size());
        assertEquals(1, run("resources | where tags.env =~ \"PROD\"").size());
        assertEquals(0, run("resources | where tags.env != \"prod\" and isnotnull(tags.env)").size());
        assertEquals(2, run("resources | where isnull(tags) or isempty(tostring(tags.env))").size());
        assertEquals(1, run("resources | where name contains \"V1\"").size());
        assertEquals(0, run("resources | where name contains_cs \"V1\"").size());
        assertEquals(2, run("resources | where id has \"storageAccounts\"").size());
        assertEquals(3, run("resources | where strlen(name) == 3").size());
        assertEquals(1, run("resources | count").getFirst().size());
        assertEquals(3L, run("resources | count").getFirst().get("Count"));
    }

    @Test
    void errorsAreReportedAsParserOrSemanticFailures() {
        KqlException parse = assertThrows(KqlException.class, () -> run("resources | where (name == "));
        assertEquals("ParserFailure", parse.code());
        KqlException table = assertThrows(KqlException.class, () -> run("nosuchtable | count"));
        assertEquals("SemanticError", table.code());
        KqlException function = assertThrows(KqlException.class, () -> run("resources | extend x = nosuchfunction(name)"));
        assertEquals("SemanticError", function.code());
        KqlException operator = assertThrows(KqlException.class, () -> run("resources | frobnicate name"));
        assertEquals("ParserFailure", operator.code());
    }
}
