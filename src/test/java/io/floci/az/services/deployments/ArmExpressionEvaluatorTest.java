package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExpressionEvaluator: the template function library")
class ArmExpressionEvaluatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUB = "00000000-0000-0000-0000-000000000001";

    /** A fixed scope: two parameters, two variables, a resource group and recorded reference and list calls. */
    private static final class FakeScope implements TemplateScope {

        final List<String> calls = new ArrayList<>();

        private static JsonNode json(String text) {
            try {
                return MAPPER.readTree(text);
            } catch (Exception e) {
                throw new IllegalArgumentException(text, e);
            }
        }

        @Override
        public JsonNode parameter(String name) {
            return switch (name.toLowerCase()) {
                case "prefix" -> json("\"app\"");
                case "count" -> json("3");
                default -> throw TemplateException.invalid("The template parameter '" + name + "' is not found.");
            };
        }

        @Override
        public JsonNode variable(String name) {
            return switch (name.toLowerCase()) {
                case "tags" -> json("{\"env\":\"dev\",\"Owner\":\"me\"}");
                case "list" -> json("[\"one\",\"two\",\"three\"]");
                default -> throw TemplateException.invalid("The template variable '" + name + "' is not found.");
            };
        }

        @Override
        public JsonNode resourceGroup() {
            return json("{\"id\":\"/subscriptions/" + SUB + "/resourceGroups/rg1\",\"name\":\"rg1\","
                + "\"location\":\"eastus\"}");
        }

        @Override
        public JsonNode subscription() {
            return json("{\"id\":\"/subscriptions/" + SUB + "\",\"subscriptionId\":\"" + SUB + "\"}");
        }

        @Override
        public JsonNode tenant() {
            return json("{\"tenantId\":\"t1\"}");
        }

        @Override
        public JsonNode deployment() {
            return json("{\"name\":\"dep1\"}");
        }

        @Override
        public JsonNode environment() {
            return json("{\"name\":\"AzureCloud\",\"suffixes\":{\"storage\":\"core.windows.net\"}}");
        }

        @Override
        public JsonNode reference(String resourceNameOrId, String apiVersion, boolean full) {
            calls.add("reference " + resourceNameOrId + " " + apiVersion + " " + full);
            return json(full ? "{\"properties\":{\"state\":\"on\"},\"location\":\"eastus\"}" : "{\"state\":\"on\"}");
        }

        @Override
        public JsonNode list(String resourceId, String action, String apiVersion, JsonNode body) {
            calls.add(action + " " + resourceId + " " + apiVersion + " " + body);
            return json("{\"keys\":[{\"value\":\"k1\"}]}");
        }
    }

    private static JsonNode eval(String expression) {
        return eval(expression, new FakeScope());
    }

    private static JsonNode eval(String expression, FakeScope scope) {
        return ExpressionEvaluator.evaluate(ExpressionParser.parse(expression), scope);
    }

    private static JsonNode json(String text) {
        return FakeScope.json(text);
    }

    /** Each expression with its expected JSON value, most taken from the examples in template-functions-*.md. */
    static Stream<Arguments> functions() {
        return Stream.of(
            // deployment, scope
            Arguments.of("[parameters('prefix')]", "\"app\""),
            Arguments.of("[PARAMETERS('Count')]", "3"),
            Arguments.of("[variables('tags').env]", "\"dev\""),
            Arguments.of("[variables('tags').owner]", "\"me\""),
            Arguments.of("[variables('list')[1]]", "\"two\""),
            Arguments.of("[resourceGroup().name]", "\"rg1\""),
            Arguments.of("[subscription().subscriptionId]", "\"" + SUB + "\""),
            Arguments.of("[tenant().tenantId]", "\"t1\""),
            Arguments.of("[deployment().name]", "\"dep1\""),
            Arguments.of("[environment().suffixes.storage]", "\"core.windows.net\""),
            // resource ids
            Arguments.of("[resourceId('Microsoft.Storage/storageAccounts', 'sa1')]",
                "\"/subscriptions/" + SUB + "/resourceGroups/rg1/providers/Microsoft.Storage/storageAccounts/sa1\""),
            Arguments.of("[resourceId('Microsoft.Network/virtualNetworks/subnets', 'vnet', 'sub1')]",
                "\"/subscriptions/" + SUB + "/resourceGroups/rg1/providers/Microsoft.Network/virtualNetworks/vnet/"
                    + "subnets/sub1\""),
            Arguments.of("[resourceId('Microsoft.Network/virtualNetworks/subnets', 'vnet/sub1')]",
                "\"/subscriptions/" + SUB + "/resourceGroups/rg1/providers/Microsoft.Network/virtualNetworks/vnet/"
                    + "subnets/sub1\""),
            Arguments.of("[resourceId('otherRg', 'Microsoft.Storage/storageAccounts', 'sa1')]",
                "\"/subscriptions/" + SUB + "/resourceGroups/otherRg/providers/Microsoft.Storage/storageAccounts/sa1\""),
            Arguments.of("[resourceId('s2', 'otherRg', 'Microsoft.Storage/storageAccounts', 'sa1')]",
                "\"/subscriptions/s2/resourceGroups/otherRg/providers/Microsoft.Storage/storageAccounts/sa1\""),
            Arguments.of("[subscriptionResourceId('Microsoft.Resources/resourceGroups', 'rg2')]",
                "\"/subscriptions/" + SUB + "/providers/Microsoft.Resources/resourceGroups/rg2\""),
            Arguments.of("[subscriptionResourceId('s2', 'Microsoft.Resources/resourceGroups', 'rg2')]",
                "\"/subscriptions/s2/providers/Microsoft.Resources/resourceGroups/rg2\""),
            Arguments.of("[tenantResourceId('Microsoft.Management/managementGroups', 'mg1')]",
                "\"/providers/Microsoft.Management/managementGroups/mg1\""),
            Arguments.of("[extensionResourceId('/subscriptions/s/resourceGroups/rg', 'Microsoft.Authorization/locks', "
                + "'lock1')]", "\"/subscriptions/s/resourceGroups/rg/providers/Microsoft.Authorization/locks/lock1\""),
            // logical and comparison
            Arguments.of("[true()]", "true"),
            Arguments.of("[false()]", "false"),
            Arguments.of("[null()]", "null"),
            Arguments.of("[not(true())]", "false"),
            Arguments.of("[and(true(), true(), false())]", "false"),
            Arguments.of("[or(false(), true())]", "true"),
            Arguments.of("[if(equals(1, 1), 'yes', 'no')]", "\"yes\""),
            Arguments.of("[bool('TRUE')]", "true"),
            Arguments.of("[bool(0)]", "false"),
            Arguments.of("[equals('a', 'A')]", "false"),
            Arguments.of("[equals(createArray(1, 2), createArray(1, 2))]", "true"),
            Arguments.of("[less(1, 10)]", "true"),
            Arguments.of("[lessOrEquals('b', 'b')]", "true"),
            Arguments.of("[greater('b', 'a')]", "true"),
            Arguments.of("[greaterOrEquals(5, 6)]", "false"),
            Arguments.of("[coalesce(null(), null(), 'default')]", "\"default\""),
            // strings
            Arguments.of("[concat('a', 1, true())]", "\"a1true\""),
            Arguments.of("[concat(createArray(1), createArray(2, 3))]", "[1,2,3]"),
            Arguments.of("[format('{0}, {1}. Formatted number: {2:N0}', 'Hello', 'User', 8175133)]",
                "\"Hello, User. Formatted number: 8,175,133\""),
            Arguments.of("[format('{{{0}}} {0,4}|{1:D3}|{2:x}', 'a', 7, 255)]", "\"{a}    a|007|ff\""),
            Arguments.of("[toLower('AbC')]", "\"abc\""),
            Arguments.of("[toUpper('AbC')]", "\"ABC\""),
            Arguments.of("[string(createObject('valueA', 10, 'valueB', 'Example Text'))]",
                "\"{\\\"valueA\\\":10,\\\"valueB\\\":\\\"Example Text\\\"}\""),
            Arguments.of("[string(createArray('a', 'b', 'c'))]", "\"[\\\"a\\\",\\\"b\\\",\\\"c\\\"]\""),
            Arguments.of("[string(5)]", "\"5\""),
            Arguments.of("[int('4')]", "4"),
            Arguments.of("[float('2.5')]", "2.5"),
            Arguments.of("[json('{\"a\": [1]}').a[0]]", "1"),
            Arguments.of("[length('abc')]", "3"),
            Arguments.of("[length(variables('tags'))]", "2"),
            Arguments.of("[empty('')]", "true"),
            Arguments.of("[empty(createObject())]", "true"),
            Arguments.of("[empty(createArray(1))]", "false"),
            Arguments.of("[first('One Two')]", "\"O\""),
            Arguments.of("[first(variables('list'))]", "\"one\""),
            Arguments.of("[last('One Two')]", "\"o\""),
            Arguments.of("[last(variables('list'))]", "\"three\""),
            Arguments.of("[take('one two', 3)]", "\"one\""),
            Arguments.of("[take(variables('list'), 9)]", "[\"one\",\"two\",\"three\"]"),
            Arguments.of("[skip('one two', 4)]", "\"two\""),
            Arguments.of("[skip(variables('list'), 2)]", "[\"three\"]"),
            Arguments.of("[split('a,b;c', createArray(',', ';'))]", "[\"a\",\"b\",\"c\"]"),
            Arguments.of("[split('a,,b', ',')]", "[\"a\",\"\",\"b\"]"),
            Arguments.of("[replace('123-123', '-', '')]", "\"123123\""),
            Arguments.of("[substring('hello', 1, 3)]", "\"ell\""),
            Arguments.of("[substring('hello', 2)]", "\"llo\""),
            Arguments.of("[trim('  x  ')]", "\"x\""),
            Arguments.of("[padLeft(123, 10, '0')]", "\"0000000123\""),
            Arguments.of("[startsWith('abcdef', 'AB')]", "true"),
            Arguments.of("[endsWith('abcdef', 'EF')]", "true"),
            Arguments.of("[contains('OneTwoThree', 'two')]", "false"),
            Arguments.of("[contains(variables('tags'), 'ENV')]", "true"),
            Arguments.of("[contains(variables('list'), 'two')]", "true"),
            Arguments.of("[indexOf('test', 'T')]", "0"),
            Arguments.of("[lastIndexOf('abcabc', 'B')]", "4"),
            Arguments.of("[indexOf(createArray('a', 'A'), 'A')]", "1"),
            Arguments.of("[indexOf('abc', 'z')]", "-1"),
            Arguments.of("[like('abcdef', 'A*E?')]", "true"),
            Arguments.of("[base64('one, two, three')]", "\"b25lLCB0d28sIHRocmVl\""),
            Arguments.of("[base64ToString('b25lLCB0d28sIHRocmVl')]", "\"one, two, three\""),
            Arguments.of("[base64ToJson('eyJvbmUiOiAiYSJ9').one]", "\"a\""),
            Arguments.of("[dataUri('Hello')]", "\"data:text/plain;charset=utf8;base64,SGVsbG8=\""),
            Arguments.of("[dataUriToString('data:;base64,SGVsbG8sIFdvcmxkIQ==')]", "\"Hello, World!\""),
            Arguments.of("[uri('http://contoso.com/resources/', 'nested/azuredeploy.json')]",
                "\"http://contoso.com/resources/nested/azuredeploy.json\""),
            Arguments.of("[uri('http://contoso.org/firstpath', 'myscript.sh')]", "\"http://contoso.org/myscript.sh\""),
            Arguments.of("[uriComponent('http://contoso.com/resources/nested/azuredeploy.json')]",
                "\"http%3A%2F%2Fcontoso.com%2Fresources%2Fnested%2Fazuredeploy.json\""),
            Arguments.of("[uriComponentToString('http%3A%2F%2Fcontoso.com%2Fa+b')]", "\"http://contoso.com/a+b\""),
            // arrays and objects
            Arguments.of("[union(createArray('one', 'two'), createArray('two', 'three'))]",
                "[\"one\",\"two\",\"three\"]"),
            Arguments.of("[union(json('{\"property\":{\"one\":\"a\",\"two\":\"b\",\"three\":\"c1\"},\"nestedArray\":[1,2]}'),"
                    + " json('{\"property\":{\"three\":\"c2\",\"four\":\"d\",\"five\":\"e\"},\"nestedArray\":[3,4]}'))]",
                "{\"property\":{\"one\":\"a\",\"two\":\"b\",\"three\":\"c2\",\"four\":\"d\",\"five\":\"e\"},"
                    + "\"nestedArray\":[3,4]}"),
            Arguments.of("[intersection(createArray('one', 'two', 'three'), createArray('two', 'three'))]",
                "[\"two\",\"three\"]"),
            Arguments.of("[intersection(createObject('one', 'a', 'two', 'b'), createObject('one', 'a', 'two', 'z'))]",
                "{\"one\":\"a\"}"),
            Arguments.of("[createObject('a', 1, 'b', createArray(true()))]", "{\"a\":1,\"b\":[true]}"),
            Arguments.of("[items(json('{\"item002\":2,\"item001\":1}'))]",
                "[{\"key\":\"item001\",\"value\":1},{\"key\":\"item002\",\"value\":2}]"),
            Arguments.of("[objectKeys(createObject('a', 1, 'b', 2))]", "[\"a\",\"b\"]"),
            Arguments.of("[shallowMerge(createArray(createObject('a', 1), createObject('a', 2, 'b', 3)))]",
                "{\"a\":2,\"b\":3}"),
            Arguments.of("[tryGet(variables('tags'), 'missing')]", "null"),
            Arguments.of("[tryGet(variables('list'), 0)]", "\"one\""),
            Arguments.of("[indexFromEnd(variables('list'), 1)]", "\"three\""),
            Arguments.of("[tryIndexFromEnd(variables('list'), 9)]", "null"),
            Arguments.of("[distinct(createArray(1, 2, 1))]", "[1,2]"),
            Arguments.of("[join(createArray('a', 'b', 1), '-')]", "\"a-b-1\""),
            Arguments.of("[array('x')]", "[\"x\"]"),
            Arguments.of("[array(createArray(1))]", "[1]"),
            Arguments.of("[range(5, 3)]", "[5,6,7]"),
            // numbers
            Arguments.of("[add(5, 3)]", "8"),
            Arguments.of("[sub(7, 3)]", "4"),
            Arguments.of("[mul(5, 3)]", "15"),
            Arguments.of("[div(8, 3)]", "2"),
            Arguments.of("[mod(7, 3)]", "1"),
            Arguments.of("[min(0, 3, 2)]", "0"),
            Arguments.of("[max(createArray(0, 3, 2))]", "3"),
            // dates
            Arguments.of("[dateTimeAdd('2023-01-01 00:00:00Z', 'P1Y')]", "\"2024-01-01T00:00:00Z\""),
            Arguments.of("[dateTimeAdd('2024-01-01 00:00:00Z', 'P1Y')]", "\"2024-12-31T00:00:00Z\""),
            Arguments.of("[dateTimeAdd('2023-02-01 00:00:00Z', 'P1M')]", "\"2023-03-03T00:00:00Z\""),
            Arguments.of("[dateTimeAdd('2020-04-07 14:53:14Z', '-P9D', 'u')]", "\"2020-03-29 14:53:14Z\""),
            Arguments.of("[dateTimeAdd('2020-04-07 14:53:14Z', 'PT1H', 'yyyyMMddTHHmmssZ')]", "\"20200407T155314Z\""),
            Arguments.of("[dateTimeFromEpoch(1683040573)]", "\"2023-05-02T15:16:13Z\""),
            Arguments.of("[dateTimeToEpoch('2023-05-02T15:16:13Z')]", "1683040573"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("functions")
    void eachFunctionAnswersItsDocumentedValue(String expression, String expected) {
        assertEquals(json(expected), eval(expression));
    }

    @Test
    void referenceAndListFunctionsCallTheScope() {
        FakeScope scope = new FakeScope();

        assertEquals(json("\"on\""), eval("[reference('sa1').state]", scope));
        assertEquals(json("\"eastus\""), eval("[reference('sa1', '2023-01-01', 'Full').location]", scope));
        assertEquals(json("\"k1\""), eval("[listKeys('sa1', '2023-01-01').keys[0].value]", scope));
        eval("[listSecrets('id', '2020-01-01', createObject('a', 1))]", scope);

        assertEquals(List.of("reference sa1 null false", "reference sa1 2023-01-01 true",
            "listKeys sa1 2023-01-01 null", "listSecrets id 2020-01-01 {\"a\":1}"), scope.calls);
    }

    @Test
    void ifEvaluatesOnlyTheSelectedBranchAndLogicalFunctionsShortCircuit() {
        FakeScope scope = new FakeScope();

        assertEquals(json("\"no\""), eval("[if(false(), reference('excluded').state, 'no')]", scope));
        assertEquals(json("false"), eval("[and(false(), parameters('missing'))]", scope));
        assertEquals(json("true"), eval("[or(true(), parameters('missing'))]", scope));
        assertTrue(scope.calls.isEmpty());
    }

    @Test
    void evaluateValueWalksObjectsAndArraysAndUnescapesLiterals() {
        JsonNode template = json("{\"name\":\"[concat(parameters('prefix'), '-sa')]\",\"tags\":{\"literal\":"
            + "\"[[not evaluated]\",\"plain\":\"text\",\"n\":1},\"list\":[\"[variables('list')[0]]\",true]}");
        JsonNode original = template.deepCopy();

        JsonNode result = ExpressionEvaluator.evaluateValue(template, new FakeScope());

        assertEquals(json("{\"name\":\"app-sa\",\"tags\":{\"literal\":\"[not evaluated]\",\"plain\":\"text\",\"n\":1},"
            + "\"list\":[\"one\",true]}"), result);
        assertEquals(original, template, "the input is not changed");
    }

    @Test
    void uniqueStringAndGuidAreDeterministicAndShapedAsAzures() {
        JsonNode unique = eval("[uniqueString(resourceGroup().id)]");
        JsonNode guid = eval("[guid(resourceGroup().id, 'a')]");

        assertEquals(unique, eval("[uniqueString(resourceGroup().id)]"));
        assertNotEquals(unique, eval("[uniqueString(resourceGroup().id, 'x')]"));
        assertTrue(unique.asText().matches("[a-z2-7]{13}"), unique.asText());
        assertEquals(guid, eval("[guid(resourceGroup().id, 'a')]"));
        assertTrue(guid.asText().matches("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
            guid.asText());
        assertTrue(eval("[newGuid()]").asText().matches("[0-9a-f-]{36}"));
    }

    @Test
    void utcNowFormatsTheCurrentTime() {
        assertTrue(eval("[utcNow()]").asText().matches("\\d{8}T\\d{6}Z"));
        assertTrue(eval("[utcNow('u')]").asText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}Z"));
        assertTrue(eval("[utcNow('d')]").asText().matches("\\d{2}/\\d{2}/\\d{4}"));
        assertTrue(eval("[utcNow('M d')]").asText().matches("\\d{1,2} \\d{1,2}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[unknownFunction()]", "[parameters('missing')]", "[variables('tags').missing]",
        "[variables('list')[7]]", "[filter(variables('list'), lambda('x', true()))]", "[copyIndex()]",
        "[contoso.custom()]", "[div(1, 0)]", "[substring('abc', 2, 5)]", "[resourceId('Microsoft.Storage/x/y', 'a')]",
        "[int('x')]", "[concat('a', createArray(1))]", "[if(1, 'a', 'b')]", "[format('{0:G}', 1)]",
        "[resourceGroup('other')]", "[range(0, 10001)]", "[json('{')]"})
    void errorsAreInvalidTemplate(String expression) {
        TemplateException error = assertThrows(TemplateException.class, () -> eval(expression));
        assertEquals(TemplateException.INVALID_TEMPLATE, error.code(), error.getMessage());
    }

    @Test
    void unsupportedFunctionsSaySo() {
        TemplateException error = assertThrows(TemplateException.class, () -> eval("[pickZones('a', 'b', 'c')]"));
        assertEquals("The template function 'pickZones' is not supported by floci-az.", error.getMessage());
    }

    @Test
    void aMissingPropertyNamesTheAvailableOnes() {
        TemplateException error = assertThrows(TemplateException.class, () -> eval("[variables('tags').nope]"));
        assertEquals("The language expression property 'nope' doesn't exist, available properties are 'env, Owner'.",
            error.getMessage());
    }

    @Test
    void literalsComeBackAsTheyAre() {
        ObjectNode expected = JsonNodeFactory.instance.objectNode();
        expected.put("x", "y");
        assertEquals(expected, ExpressionEvaluator.evaluateValue(json("{\"x\":\"y\"}"), new FakeScope()));
        assertEquals(json("\"it's\""), eval("['it''s']"));
        assertEquals(json("-5"), eval("[-5]"));
        assertEquals(0, eval("[createObject()]").size());
    }
}
