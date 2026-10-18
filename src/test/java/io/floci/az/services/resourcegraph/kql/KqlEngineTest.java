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
                "tags", row("env", "prod"), "properties", row("creationTime", "2026-01-02T03:04:05Z"), "systemData", null));
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

    @Test
    void joinKindsFollowKustoRowAndColumnRules() {
        // innerunique (the default) keeps the first left row of a key and every right match.
        List<Map<String, Object>> unique = run("""
                resources | project id, name
                | join (resources | project id, location) on id
                """);
        assertEquals(3, unique.size());
        List<Map<String, Object>> dedup = run("""
                resources | project key = location
                | join (resources | project key = location, name) on key
                | order by name asc
                """);
        assertEquals(List.of("kv1", "sa1", "sa2"), dedup.stream().map(r -> r.get("name")).toList());
        // fullouter: unmatched rows carry the same column names as matched ones, with nulls on the missing side.
        List<Map<String, Object>> full = run("""
                resources | where name == "sa1" | project id, name
                | join kind=fullouter (resources | where name != "sa1" | project id, name) on id
                | order by coalesce(name, name1) asc
                """);
        assertEquals(3, full.size());
        for (Map<String, Object> row : full) {
            assertEquals(List.of("id", "name", "id1", "name1"), new ArrayList<>(row.keySet()));
        }
        assertEquals("kv1", full.get(0).get("name1"));
        assertNull(full.get(0).get("name"));
        assertEquals("sa1", full.get(1).get("name"));
        assertNull(full.get(1).get("id1"));
    }

    @Test
    void selectorAggregatesExpandTheChosenRow() {
        List<Map<String, Object>> rows = run("""
                resources | extend created = todatetime(properties.creationTime)
                | summarize arg_max(created, *) by subscriptionId
                """);
        assertEquals(1, rows.size());
        assertEquals("sa1", rows.getFirst().get("name"));
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), rows.getFirst().get("created"));
        assertTrue(rows.getFirst().containsKey("location"));
        List<Map<String, Object>> named = run("resources | summarize arg_min(name, id, location)");
        assertEquals(List.of("name", "id", "location"), new ArrayList<>(named.getFirst().keySet()));
        assertEquals("kv1", named.getFirst().get("name"));
        assertEquals("eastus", named.getFirst().get("location"));
        List<Map<String, Object>> pair = run("resources | summarize arg_max(name, location)");
        assertEquals(List.of("name", "location"), new ArrayList<>(pair.getFirst().keySet()));
        assertEquals("sa2", pair.getFirst().get("name"));
    }

    @Test
    void calculatedColumnsAreNumberedByPosition() {
        Map<String, Object> row = run("resources | take 1 | project 1 + 1, 2 + 2, name").getFirst();
        assertEquals(List.of("Column1", "Column2", "name"), new ArrayList<>(row.keySet()));
        assertEquals(2L, row.get("Column1"));
        assertEquals(4L, row.get("Column2"));
    }

    @Test
    void rangesExponentsAndIntegerPrecision() {
        assertEquals(3, run("resources | where strlen(name) between (1 .. 10)").size());
        assertEquals(3, run("resources | where strlen(name) between (1..10)").size());
        assertEquals(0, run("resources | where strlen(name) between (10..20)").size());
        Map<String, Object> row = run("resources | take 1 | project a = 1e-3, b = 1e+3, c = toint('3000000000'), d = tolong('3000000000')").getFirst();
        assertEquals(0.001, (Double) row.get("a"), 1e-12);
        assertEquals(1000.0, (Double) row.get("b"), 1e-12);
        assertNull(row.get("c"));
        assertEquals(3000000000L, row.get("d"));
        assertEquals(0, run("resources | where 9007199254740993 == 9007199254740992").size());
        assertEquals(3, run("resources | where 9007199254740993 > 9007199254740992").size());
    }

    @Test
    void functionOptionsApply() {
        Map<String, Object> row = run("""
                resources | take 1
                | project r = round(3.14159, 2), s = startofday(datetime(2026-01-02T10:00:00Z), 1), i = indexof('abcabc', 'a', 1)
                """).getFirst();
        assertEquals(3.14, (Double) row.get("r"), 1e-12);
        assertEquals(Instant.parse("2026-01-03T00:00:00Z"), row.get("s"));
        assertEquals(3L, row.get("i"));
    }

    @Test
    void nullsStayNullThroughComparisons() {
        // Resources with no creationTime are neither before nor after now: a negated comparison keeps them out.
        assertEquals(1, run("resources | where todatetime(properties.creationTime) < now()").size());
        assertEquals(0, run("resources | where not(todatetime(properties.creationTime) < now())").size());
        assertEquals(2, run("resources | where isnull(todatetime(properties.creationTime))").size());
    }

    @Test
    void unknownColumnsAreSemanticErrors() {
        KqlException missing = assertThrows(KqlException.class, () -> run("resources | project misspelledColumn"));
        assertTrue(missing.getMessage().contains("misspelledColumn"));
        assertThrows(KqlException.class, () -> run("resources | where nosuch == 1"));
        assertThrows(KqlException.class, () -> run("resources | extend x = name | project x, y"));
        // Names are checked before the query runs, so an empty table rejects them too.
        assertThrows(KqlException.class, () -> run("authorizationresources | project nosuchfunction(id)"));
        assertThrows(KqlException.class, () -> run("resources | summarize nosuchagg(name)"));
        KqlEngine.Tables published = new KqlEngine.Tables() {
            @Override
            public List<Map<String, Object>> rows(String table) {
                return List.of();
            }

            @Override
            public List<String> columns(String table) {
                return List.of("id", "name", "properties");
            }
        };
        assertThrows(KqlException.class, () -> KqlEngine.run(Kql.parse("resources | project misspelled"), published));
        assertEquals(0, KqlEngine.run(Kql.parse("resources | project name, properties.x"), published).size());
        // Dynamic properties below a column are data, not schema.
        assertEquals(3, run("resources | project properties.nosuch").size());
        assertEquals(1, run("resources | extend x = name | project x | where x == 'sa1'").size());
        assertEquals(3, run("resources | distinct * | project name | where name != ''").size());
    }

    @Test
    void chainedJoinsKeepEveryField() {
        List<Map<String, Object>> rows = run("""
                resources | project id, name
                | join kind=inner (resources | project id, name) on id
                | join kind=inner (resources | project id, name) on id
                | where name == "sa1"
                """);
        assertEquals(1, rows.size());
        assertEquals(List.of("id", "name", "id1", "name1", "id2", "name2"), new ArrayList<>(rows.getFirst().keySet()));
        assertEquals("sa1", rows.getFirst().get("name2"));
    }

    @Test
    void selectorsSkipMissingValues() {
        Map<String, Object> row = run("resources | summarize arg_min(todatetime(properties.creationTime), name)").getFirst();
        assertEquals("sa1", row.get("name"));
        // Every value missing: the first row stands, as before.
        assertEquals("sa2", run("resources | where name != 'sa1' | summarize arg_max(todatetime(properties.creationTime), name)").getFirst().get("name"));
    }

    @Test
    void extendSeesTheColumnsBeforeIt() {
        Map<String, Object> row = run("resources | where name == 'sa1' | extend x = name, y = strcat(x, '!'), x = toupper(x) | project x, y").getFirst();
        assertEquals("SA1", row.get("x"));
        assertEquals("sa1!", row.get("y"));
    }

    @Test
    void literalsFollowKusto() {
        assertEquals(1, run("resources | where todatetime(properties.creationTime) > ago(36500d)").size());
        Map<String, Object> row = run("""
                resources | take 1
                | project q = 5 / 2, r = 5.0 / 2, big = tolong('9223372036854775808'), nan = tolong('NaN'),
                          u = "\\u0041", v = @'a ''b''', span = 90m
                """).getFirst();
        assertEquals(2L, row.get("q"));
        assertEquals(2.5, (Double) row.get("r"), 1e-12);
        assertNull(row.get("big"));
        assertNull(row.get("nan"));
        assertEquals("A", row.get("u"));
        assertEquals("a 'b'", row.get("v"));
        assertEquals(java.time.Duration.ofMinutes(90), row.get("span"));
        Map<String, Object> spans = run("resources | take 1 | project neg = -90m, micro = 7microseconds, ticks = 7ticks, one = 1tick").getFirst();
        assertEquals(java.time.Duration.ofMinutes(-90), spans.get("neg"));
        assertEquals(java.time.Duration.ofNanos(7_000), spans.get("micro"));
        assertEquals(java.time.Duration.ofNanos(700), spans.get("ticks"));
        assertEquals(java.time.Duration.ofNanos(100), spans.get("one"));
        // now(-7d) is seven days ago, not the current time.
        assertEquals(1, run("resources | where todatetime(properties.creationTime) < now(-7d)").size());
        assertEquals(0, run("resources | where todatetime(properties.creationTime) > now(-7d)").size());
    }

    @Test
    void dynamicKeysAreCaseSensitive() {
        assertEquals(1, run("resources | where tags.env == 'prod'").size());
        assertEquals(0, run("resources | where tags.Env == 'prod'").size());
        assertEquals(1, run("resources | where isnull(tags.Env) and isnotnull(tags.env)").size());
    }
}
