package io.floci.az.services.policy.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ArmExpressions: template functions a policy rule may use")
class ArmExpressionsTest {

    private static EvaluationContext context() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", "sa1");
        content.put("location", "East US");
        content.put("tags", Map.of("env", "prod"));
        content.put("properties", Map.of(
                "minimumTlsVersion", "TLS1_2",
                "networkAcls", Map.of("ipRules", List.of(Map.of("value", "10.0.0.1", "action", "Allow")))));
        Map<String, Object> requestContext = new LinkedHashMap<>();
        requestContext.put("apiVersion", "2023-01-01");
        requestContext.put("identity", Map.of("idtyp", "app", "appid", "abc"));
        Map<String, Object> group = Map.of("name", "rg1", "tags", Map.of("owner", "ops"));
        EvaluationContext ctx = new EvaluationContext(content, null, "Microsoft.Storage/storageAccounts", "sa1",
                requestContext, group, Map.of("subscriptionId", "s1"));
        ctx.parameters = Map.of("allowed", List.of("eastus", "westus"), "effect", "Deny", "limit", 3);
        ctx.policy = EvaluationContext.policyFacts("/a", "/d", "/s", "ref-1");
        return ctx;
    }

    private static Object eval(String expression) {
        return ArmExpressions.evaluate(expression, context());
    }

    @Test
    void literalsAndEscapes() {
        assertEquals("plain", eval("plain"));
        assertEquals("[not an expression]", eval("[[not an expression]"));
        assertEquals("it's", eval("['it''s']"));
        assertEquals(42L, eval("[42]"));
        assertEquals(true, eval("[true()]"));
        assertNull(eval("[null()]"));
    }

    @Test
    void policyFunctions() {
        assertEquals("Deny", eval("[parameters('effect')]"));
        assertEquals("TLS1_2", eval("[field('Microsoft.Storage/storageAccounts/minimumTlsVersion')]"));
        assertEquals("", eval("[field('Microsoft.Storage/storageAccounts/missing')]"));
        assertEquals(List.of("10.0.0.1"), eval("[field('Microsoft.Storage/storageAccounts/networkAcls.ipRules[*].value')]"));
        assertEquals("ref-1", eval("[policy().definitionReferenceId]"));
        assertEquals("app", eval("[requestContext().identity.idtyp]"));
        assertEquals("app", eval("[tryGet(requestContext().identity, 'idtyp')]"));
        assertNull(eval("[tryGet(requestContext().identity, 'acrs')]"));
        assertEquals("rg1", eval("[resourceGroup().name]"));
        assertEquals("ops", eval("[resourceGroup().tags['owner']]"));
        assertEquals("s1", eval("[subscription().subscriptionId]"));
        assertEquals("2023-01-01", eval("[requestContext().apiVersion]"));
        assertTrue(((String) eval("[toLower(string(requestContext()))]")).contains("\"idtyp\":\"app\""));
    }

    @Test
    void logicAndComparison() {
        assertEquals(true, eval("[and(true(), not(false()))]"));
        assertEquals(false, eval("[or(false(), equals('a', 'A'))]"));
        assertEquals(true, eval("[equals(1, 1.0)]"));
        assertEquals(true, eval("[greaterOrEquals(length(field('name')), 3)]"));
        assertEquals("yes", eval("[if(contains(parameters('allowed'), 'eastus'), 'yes', 'no')]"));
        assertEquals("short", eval("[if(greaterOrEquals(length(field('name')), 10), substring(field('name'), 0, 10), 'short')]"));
        assertEquals("fallback", eval("[coalesce(null(), 'fallback')]"));
    }

    @Test
    void stringsAndCollections() {
        assertEquals("sa1-prod", eval("[concat(field('name'), '-', field('tags.env'))]"));
        assertEquals(List.of("a", "b", "c"), eval("[split('a,b;c', createArray(',', ';'))]"));
        assertEquals("a", eval("[first(split('a-b', '-'))]"));
        assertEquals("b", eval("[last(split('a-b', '-'))]"));
        assertEquals(2L, eval("[length(parameters('allowed'))]"));
        assertEquals(true, eval("[empty(field('Microsoft.Storage/storageAccounts/missing'))]"));
        assertEquals(true, eval("[startsWith('Hello', 'he')]"));
        assertEquals("HELLO", eval("[toUpper('hello')]"));
        assertEquals("h-llo", eval("[replace('hello', 'e', '-')]"));
        assertEquals(List.of("a", "b", "c"), eval("[union(createArray('a', 'b'), createArray('b', 'c'))]"));
        assertEquals(List.of("b"), eval("[intersection(createArray('a', 'b'), createArray('b', 'c'))]"));
        assertEquals(12L, eval("[int('12')]"));
        assertEquals(7L, eval("[add(3, mul(2, 2))]"));
        assertEquals(Map.of("a", 1), eval("[json('{\"a\": 1}')]"));
        assertEquals("x", eval("[take('xyz', 1)]"));
        assertEquals(3L, eval("[int(parameters('limit'))]"));
    }

    @Test
    void ipRanges() {
        assertEquals(true, eval("[ipRangeContains('10.0.0.0/24', '10.0.0.128')]"));
        assertEquals(false, eval("[ipRangeContains('10.0.0.0/24', '10.0.1.0/24')]"));
        assertEquals(true, eval("[ipRangeContains('192.168.0.1-192.168.0.9', '192.168.0.5')]"));
        assertThrows(ArmExpressions.EvaluationException.class, () -> eval("[ipRangeContains('10.0.0.0/8', '2001:db8::1')]"));
    }

    @Test
    void failuresAreEvaluationExceptions() {
        assertThrows(ArmExpressions.EvaluationException.class, () -> eval("[substring(field('name'), 0, 20)]"));
        assertThrows(ArmExpressions.EvaluationException.class, () -> eval("[parameters('missing')]"));
        assertThrows(ArmExpressions.EvaluationException.class, () -> eval("[noSuchFunction(1)]"));
        assertThrows(ArmExpressions.EvaluationException.class, () -> eval("[concat('unterminated]"));
        assertThrows(ArmExpressions.EvaluationException.class, () -> eval("[padLeft('x', 2, '')]"));
        assertFalse(((Boolean) eval("[contains(field('tags'), 'missing')]")));
    }
}
