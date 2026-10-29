package io.floci.az.services.policy.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PolicyRuleEvaluator: conditions, operators, array aliases and count expressions")
class PolicyRuleEvaluatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static EvaluationContext nsg(String... sourcePrefixes) {
        List<Object> rules = new ArrayList<>();
        for (int i = 0; i < sourcePrefixes.length; i++) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("access", i == 0 ? "Allow" : "Deny");
            properties.put("direction", "Inbound");
            properties.put("destinationPortRange", "22");
            properties.put("sourceAddressPrefix", sourcePrefixes[i]);
            rules.add(Map.of("name", "r" + i, "properties", properties));
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", "nsg1");
        content.put("location", "westeurope");
        content.put("tags", Map.of("env", "Prod"));
        content.put("properties", Map.of("securityRules", rules));
        EvaluationContext ctx = new EvaluationContext(content, null, "Microsoft.Network/networkSecurityGroups", "nsg1",
                Map.of("apiVersion", "2023-09-01", "identity", Map.of()), null, Map.of());
        ctx.parameters = Map.of("allowedLocations", List.of("westeurope", "northeurope"), "names", List.of("prod*", "dev*"));
        return ctx;
    }

    private static boolean evaluate(String condition, EvaluationContext ctx) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(condition, Map.class);
            return PolicyRuleEvaluator.evaluate(parsed, ctx, new ArrayList<>());
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void operatorsAreCaseInsensitiveExceptMatch() {
        EvaluationContext ctx = nsg("*");
        assertTrue(evaluate("{\"field\": \"tags['env']\", \"equals\": \"prod\"}", ctx));
        assertTrue(evaluate("{\"field\": \"tags.env\", \"in\": [\"PROD\", \"dev\"]}", ctx));
        assertTrue(evaluate("{\"field\": \"location\", \"in\": \"[parameters('allowedLocations')]\"}", ctx));
        assertTrue(evaluate("{\"field\": \"name\", \"like\": \"nsg*\"}", ctx));
        assertTrue(evaluate("{\"field\": \"name\", \"match\": \"???#\"}", ctx));
        assertFalse(evaluate("{\"field\": \"name\", \"match\": \"NSG#\"}", ctx));
        assertTrue(evaluate("{\"field\": \"name\", \"matchInsensitively\": \"NSG#\"}", ctx));
        assertTrue(evaluate("{\"field\": \"tags\", \"containsKey\": \"ENV\"}", ctx));
        assertTrue(evaluate("{\"field\": \"tags['missing']\", \"exists\": \"false\"}", ctx));
        assertTrue(evaluate("{\"field\": \"Microsoft.Network/networkSecurityGroups/securityRules\", \"exists\": \"true\"}", ctx));
        assertTrue(evaluate("{\"value\": \"[length(field('Microsoft.Network/networkSecurityGroups/securityRules[*]'))]\", \"greaterOrEquals\": 1}", ctx));
        assertTrue(evaluate("{\"value\": \"[requestContext().apiVersion]\", \"greaterOrEquals\": \"2019-04-01\"}", ctx));
        assertFalse(evaluate("{\"field\": \"Microsoft.Compute/virtualMachines/osProfile\", \"exists\": \"true\"}", ctx));
    }

    @Test
    void arrayAliasConditionsHoldForEveryMember() {
        assertTrue(evaluate("{\"field\": \"Microsoft.Network/networkSecurityGroups/securityRules[*].sourceAddressPrefix\", \"notEquals\": \"*\"}",
                nsg("10.0.0.0/8", "10.1.0.0/16")));
        assertFalse(evaluate("{\"field\": \"Microsoft.Network/networkSecurityGroups/securityRules[*].sourceAddressPrefix\", \"notEquals\": \"*\"}",
                nsg("10.0.0.0/8", "*")));
        assertTrue(evaluate("{\"field\": \"Microsoft.Network/networkSecurityGroups/securityRules[*].sourceAddressPrefix\", \"notEquals\": \"*\"}",
                nsg()));
        assertTrue(evaluate("{\"field\": \"Microsoft.Network/networkSecurityGroups/securityRules[*].direction\", \"equals\": \"inbound\"}",
                nsg("*", "*")));
    }

    @Test
    void fieldCountEnumeratesMembersIndividually() {
        String openSsh = """
                {"count": {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*]",
                           "where": {"allOf": [
                              {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*].access", "equals": "Allow"},
                              {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*].sourceAddressPrefix", "equals": "*"}]}},
                 "greater": 0}""";
        assertTrue(evaluate(openSsh, nsg("*", "10.0.0.0/8")));
        assertFalse(evaluate(openSsh, nsg("10.0.0.0/8", "*")));
        assertTrue(evaluate("{\"count\": {\"field\": \"Microsoft.Network/networkSecurityGroups/securityRules[*]\"}, \"equals\": 2}", nsg("a", "b")));
        assertTrue(evaluate("{\"count\": {\"field\": \"Microsoft.Network/networkSecurityGroups/securityRules[*]\"}, \"equals\": 0}", nsg()));
        String viaCurrent = """
                {"count": {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*]",
                           "where": {"value": "[current('Microsoft.Network/networkSecurityGroups/securityRules[*].sourceAddressPrefix')]", "equals": "*"}},
                 "equals": 1}""";
        assertTrue(evaluate(viaCurrent, nsg("*", "10.0.0.0/8")));
        String viaFirstField = """
                {"count": {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*]",
                           "where": {"value": "[first(field('Microsoft.Network/networkSecurityGroups/securityRules[*].sourceAddressPrefix'))]", "equals": "*"}},
                 "equals": 1}""";
        assertTrue(evaluate(viaFirstField, nsg("*", "10.0.0.0/8")));
        // field() of an array alias in focus is a one-member array holding the whole value, not its first character.
        String wholePrefix = """
                {"count": {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*]",
                           "where": {"value": "[first(field('Microsoft.Network/networkSecurityGroups/securityRules[*].sourceAddressPrefix'))]", "equals": "10.0.0.0/8"}},
                 "equals": 1}""";
        assertTrue(evaluate(wholePrefix, nsg("*", "10.0.0.0/8")));
    }

    @Test
    void fieldCountOverAPropertyAliasFocusesEachMember() {
        // Counting securityRules[*].access with a where on the same alias sees one member at a time.
        String allowed = """
                {"count": {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*].access",
                           "where": {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*].access", "equals": "Allow"}},
                 "equals": 1}""";
        assertTrue(evaluate(allowed, nsg("*", "10.0.0.0/8")));
        String allowedFromAnywhere = """
                {"count": {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*].access",
                           "where": {"allOf": [
                              {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*].access", "equals": "Allow"},
                              {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*].sourceAddressPrefix", "equals": "*"}]}},
                 "equals": 1}""";
        assertTrue(evaluate(allowedFromAnywhere, nsg("*", "10.0.0.0/8")));
        assertFalse(evaluate(allowedFromAnywhere, nsg("10.0.0.0/8", "*")));
    }

    @Test
    void missingArrayMembersResolveToNull() {
        EvaluationContext ctx = nsg("10.0.0.0/8", null);
        assertTrue(evaluate("{\"field\": \"Microsoft.Network/networkSecurityGroups/securityRules[*].sourceAddressPrefix\", \"notEquals\": \"*\"}", ctx));
        assertTrue(evaluate("{\"count\": {\"field\": \"Microsoft.Network/networkSecurityGroups/securityRules[*].sourceAddressPrefix\", \"where\": {\"field\": \"Microsoft.Network/networkSecurityGroups/securityRules[*].sourceAddressPrefix\", \"exists\": \"false\"}}, \"equals\": 1}", ctx));
    }

    @Test
    void valueCountBindsTheCurrentMember() {
        String namePattern = """
                {"count": {"value": "[parameters('names')]", "name": "pattern",
                           "where": {"field": "name", "like": "[current('pattern')]"}},
                 "greater": 0}""";
        assertFalse(evaluate(namePattern, nsg()));
        String unnamed = """
                {"count": {"value": ["nsg*"], "where": {"field": "name", "like": "[current()]"}}, "greater": 0}""";
        assertTrue(evaluate(unnamed, nsg()));
    }

    @Test
    void logicalOperatorsNest() {
        EvaluationContext ctx = nsg("*");
        assertTrue(evaluate("{\"allOf\": [{\"field\": \"type\", \"equals\": \"Microsoft.Network/networkSecurityGroups\"}, {\"not\": {\"field\": \"location\", \"equals\": \"eastus\"}}]}", ctx));
        assertFalse(evaluate("{\"anyOf\": [{\"field\": \"location\", \"equals\": \"eastus\"}, {\"field\": \"kind\", \"exists\": \"true\"}]}", ctx));
    }

    @Test
    void traceRecordsEachLeafInAzuresShape() {
        List<Map<String, Object>> trace = new ArrayList<>();
        Map<String, Object> condition = Map.of("field", "location", "notIn", List.of("eastus"));
        assertTrue(PolicyRuleEvaluator.evaluate(condition, nsg(), trace));
        assertEquals(1, trace.size());
        assertEquals("True", trace.get(0).get("result"));
        assertEquals("Field", trace.get(0).get("expressionKind"));
        assertEquals("location", trace.get(0).get("path"));
        assertEquals("westeurope", trace.get(0).get("expressionValue"));
        assertEquals("NotIn", trace.get(0).get("operator"));
    }

    @Test
    void inspectsTagsFindsTagsFieldsAnywhereInTheCondition() {
        EvaluationContext ctx = nsg();
        ctx.parameters = Map.of("tagName", "costCenter", "property", "location");
        assertTrue(inspectsTags("{\"field\": \"tags['env']\", \"exists\": \"false\"}", ctx));
        assertTrue(inspectsTags("{\"allOf\": [{\"field\": \"type\", \"equals\": \"x\"}, {\"not\": {\"field\": \"tags.env\", \"equals\": \"a\"}}]}", ctx));
        assertTrue(inspectsTags("{\"field\": \"tags\", \"containsKey\": \"env\"}", ctx));
        assertTrue(inspectsTags("{\"field\": \"[concat('tags[', parameters('tagName'), ']')]\", \"exists\": \"false\"}", ctx));
        assertTrue(inspectsTags("{\"value\": \"[length(field('tags'))]\", \"less\": 2}", ctx));
        assertTrue(inspectsTags("{\"count\": {\"field\": \"tags[*]\"}, \"equals\": 0}", ctx));
        assertFalse(inspectsTags("{\"allOf\": [{\"field\": \"type\", \"equals\": \"x\"}, {\"field\": \"location\", \"notIn\": [\"tags\"]}]}", ctx));
        assertFalse(inspectsTags("{\"field\": \"[parameters('property')]\", \"equals\": \"eastus\"}", ctx));
        assertFalse(inspectsTags("{\"value\": \"[resourceGroup().tags]\", \"exists\": \"true\"}", ctx));
    }

    @Test
    void inspectsTagsResolvesTheFieldAValueExpressionNames() {
        EvaluationContext ctx = nsg();
        ctx.parameters = Map.of("tagField", "tags['owner']", "tagName", "costCenter", "property", "location");
        assertTrue(inspectsTags("{\"value\": \"[field(parameters('tagField'))]\", \"exists\": \"false\"}", ctx));
        assertTrue(inspectsTags("{\"value\": \"[empty(field(concat('tags[', parameters('tagName'), ']')))]\", \"equals\": true}", ctx));
        assertFalse(inspectsTags("{\"value\": \"[field(parameters('property'))]\", \"equals\": \"eastus\"}", ctx));
        assertFalse(inspectsTags("{\"value\": \"[toLower(field('location'))]\", \"equals\": \"eastus\"}", ctx));
        // An argument the context cannot settle keeps the rule eligible.
        assertTrue(inspectsTags("{\"value\": \"[field(parameters('missing'))]\", \"exists\": \"false\"}", ctx));
    }

    private static boolean inspectsTags(String condition, EvaluationContext ctx) {
        try {
            return PolicyRuleEvaluator.inspectsTags(MAPPER.readValue(condition, Map.class), ctx);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
