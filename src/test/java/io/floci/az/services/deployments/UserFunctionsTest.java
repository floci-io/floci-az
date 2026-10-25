package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UserFunctions: a template's own namespace.member() functions")
class UserFunctionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The documented contoso.uniqueName, plus members that break each documented restriction. */
    private static final String TEMPLATE = """
        {"parameters": {"prefix": {"type": "string", "defaultValue": "Store"}},
         "variables": {"suffix": "x"},
         "functions": [
           {"namespace": "contoso", "members": {
             "uniqueName": {"parameters": [{"name": "namePrefix", "type": "string"}],
               "output": {"type": "string",
                          "value": "[concat(toLower(parameters('namePrefix')), resourceGroup().name)]"}},
             "pair": {"parameters": [{"name": "a", "type": "int"}, {"name": "b", "type": "int"}],
               "output": {"type": "array", "value": "[createArray(parameters('a'), parameters('b'))]"}},
             "readsTemplateParameter": {"output": {"type": "string", "value": "[parameters('prefix')]"}},
             "readsVariable": {"output": {"type": "string", "value": "[variables('suffix')]"}},
             "callsAnother": {"output": {"type": "string", "value": "[contoso.uniqueName('a')]"}},
             "references": {"output": {"type": "object", "value": "[reference('sa')]"}},
             "lists": {"output": {"type": "object", "value": "[listKeys('id', '2023-01-01')]"}},
             "wrongOutput": {"output": {"type": "int", "value": "text"}}}}]}
        """;

    private static JsonNode eval(String expression) throws Exception {
        DeploymentTemplate template = DeploymentTemplate.parse(MAPPER.readTree(TEMPLATE));
        return ExpressionEvaluator.evaluate(ExpressionParser.parse(expression), new Scope(template));
    }

    @Test
    void aFunctionEvaluatesItsOutputOverItsOwnParametersAndTheScopeFunctions() throws Exception {
        assertEquals("storerg1", eval("[contoso.uniqueName('Store')]").asText());
        assertEquals("storerg1", eval("[CONTOSO.UNIQUENAME(parameters('prefix'))]").asText());
        assertEquals("[1,2]", eval("[contoso.pair(1, 2)]").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[contoso.readsTemplateParameter()]",
        "[contoso.readsVariable()]",
        "[contoso.callsAnother()]",
        "[contoso.references()]",
        "[contoso.lists()]",
        "[contoso.wrongOutput()]",
        "[contoso.uniqueName()]",
        "[contoso.uniqueName(1)]",
        "[contoso.pair(1)]",
        "[contoso.missing()]",
        "[other.uniqueName('a')]",
    })
    void eachDocumentedRestrictionIsAnInvalidTemplate(String expression) {
        TemplateException error = assertThrows(TemplateException.class, () -> eval(expression));
        assertEquals(TemplateException.INVALID_TEMPLATE, error.code(), error.getMessage());
    }

    @Test
    void aRestrictionNamesTheFunctionAndWhatItMayUse() {
        TemplateException error = assertThrows(TemplateException.class, () -> eval("[contoso.readsVariable()]"));
        assertTrue(error.getMessage().contains("'contoso.readsVariable' can't access variables"), error.getMessage());
    }

    /** A deployment scope over {@code template}: resource group rg1, parameters from their defaults. */
    private record Scope(DeploymentTemplate template) implements TemplateScope {
        @Override
        public JsonNode parameter(String name) {
            return template.parameter(name).defaultValue();
        }

        @Override
        public JsonNode variable(String name) {
            return template.variables().get(name);
        }

        @Override
        public JsonNode resourceGroup() {
            return JsonNodeFactory.instance.objectNode().put("name", "rg1");
        }

        @Override
        public JsonNode subscription() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public JsonNode tenant() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public JsonNode deployment() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public JsonNode environment() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public JsonNode reference(String resourceNameOrId, String apiVersion, boolean full) {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public JsonNode list(String resourceId, String action, String apiVersion, JsonNode body) {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public JsonNode userFunction(String name, List<JsonNode> arguments) {
            return template.functions().call(name, arguments, this);
        }
    }
}
