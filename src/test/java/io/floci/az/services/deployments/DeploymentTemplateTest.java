package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DeploymentTemplate: what Phase 1 reads and what it refuses")
class DeploymentTemplateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void parametersVariablesResourcesAndOutputsAreRead() throws Exception {
        DeploymentTemplate template = DeploymentTemplate.parse(json("""
            {"$schema": "x", "contentVersion": "1.0.0.0",
             "parameters": {"Name": {"type": "String", "defaultValue": "sa1", "allowedValues": ["sa1", "sa2"]},
                            "secret": {"type": "secureString"}},
             "variables": {"v": "[parameters('Name')]"},
             "resources": [{"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01",
                            "name": "[parameters('name')]", "location": "eastus"}],
             "outputs": {"id": {"type": "string", "value": "x"}}}
            """));

        assertEquals("Name", template.parameter("name").name());
        assertEquals("string", template.parameter("NAME").type());
        assertEquals("sa1", template.parameter("name").defaultValue().asText());
        assertEquals(2, template.parameter("name").allowedValues().size());
        assertTrue(template.parameter("secret").secure());
        assertFalse(template.parameter("name").secure());
        assertNull(template.parameter("secret").defaultValue());
        assertNull(template.parameter("missing"));
        assertEquals("[parameters('Name')]", template.variables().path("v").asText());
        assertEquals(1, template.resources().size());
        assertEquals(0, template.resources().getFirst().index());
        assertEquals("string", template.outputs().path("id").path("type").asText());
    }

    @Test
    void aTemplateWithNoSectionsIsEmpty() throws Exception {
        DeploymentTemplate template = DeploymentTemplate.parse(json("{}"));

        assertTrue(template.parameters().isEmpty());
        assertTrue(template.resources().isEmpty());
        assertTrue(template.variables().isEmpty());
        assertTrue(template.outputs().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"functions\": [{\"namespace\": \"n\"}]}",
        "{\"languageVersion\": \"2.0\", \"resources\": {\"sa\": {}}}",
        "{\"resources\": [{\"type\": \"Microsoft.Resources/deployments\", \"apiVersion\": \"1\", \"name\": \"m\","
            + " \"properties\": {\"mode\": \"Incremental\", \"templateLink\": {\"uri\": \"https://x\"}}}]}",
    })
    void whatPhaseOneDoesNotServeIsRefusedNotSkipped(String template) {
        TemplateException refused = assertThrows(TemplateException.class,
            () -> DeploymentTemplate.parse(json(template)));

        assertEquals("InvalidTemplate", refused.code());
        assertTrue(refused.getMessage().startsWith("Deployment template validation failed: '"), refused.getMessage());
        assertTrue(refused.getMessage().contains("does not support"), refused.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[]",
        "{\"parameters\": {\"p\": {\"type\": \"number\"}}}",
        "{\"resources\": [{\"type\": \"Microsoft.Storage/storageAccounts\", \"name\": \"a\"}]}",
        "{\"resources\": [\"not an object\"]}",
        "{\"resources\": 3}",
        "{\"outputs\": []}",
        "{\"resources\": [{\"type\": \"Microsoft.Resources/deployments\", \"apiVersion\": \"1\", \"name\": \"m\"}]}",
        "{\"resources\": [{\"type\": \"Microsoft.Resources/deployments\", \"apiVersion\": \"1\", \"name\": \"m\","
            + " \"properties\": {\"template\": {}, \"expressionEvaluationOptions\": {\"scope\": \"sideways\"}}}]}",
        "{\"resources\": [{\"type\": \"Microsoft.Storage/storageAccounts\", \"apiVersion\": \"1\", \"name\": \"a\","
            + " \"resources\": [{}]}]}",
        "{\"resources\": [{\"type\": \"Microsoft.Network/virtualNetworks\", \"apiVersion\": \"1\", \"name\": \"v\","
            + " \"resources\": [{\"type\": \"subnets\", \"apiVersion\": \"1\", \"name\": \"[concat('s', copyIndex())]\","
            + " \"copy\": {\"name\": \"c\", \"count\": 2}}]}]}",
        "{\"resources\": [{\"type\": \"Microsoft.Network/virtualNetworks\", \"apiVersion\": \"1\","
            + " \"name\": \"[concat('v', copyIndex())]\", \"copy\": {\"name\": \"c\", \"count\": 2},"
            + " \"resources\": [{\"type\": \"subnets\", \"apiVersion\": \"1\", \"name\": \"s\"}]}]}",
        "{\"resources\": [{\"type\": \"Microsoft.Storage/storageAccounts\", \"apiVersion\": \"1\", \"name\": \"a\","
            + " \"copy\": {\"count\": 2}}]}",
        "{\"resources\": [{\"type\": \"Microsoft.Storage/storageAccounts\", \"apiVersion\": \"1\", \"name\": \"a\","
            + " \"copy\": {\"name\": \"c\", \"count\": 2, \"mode\": \"sideways\"}}]}",
        "{\"resources\": [{\"type\": \"Microsoft.Storage/storageAccounts\", \"apiVersion\": \"1\", \"name\": \"a\","
            + " \"copy\": {\"name\": \"c\", \"count\": 2, \"batchSize\": 0}}]}",
        "{\"resources\": [{\"type\": \"Microsoft.Network/virtualNetworks/subnets\", \"apiVersion\": \"1\","
            + " \"name\": \"v/s\", \"properties\": {\"copy\": [{\"name\": \"x\", \"count\": 1, \"input\": 1}]}}]}",
    })
    void aMalformedTemplateIsAnInvalidTemplate(String template) {
        assertEquals("InvalidTemplate",
            assertThrows(TemplateException.class, () -> DeploymentTemplate.parse(json(template))).code());
    }

    @Test
    void nestedChildrenBecomeTopLevelResourcesRightAfterTheirParent() throws Exception {
        DeploymentTemplate template = DeploymentTemplate.parse(json("""
            {"resources": [
              {"type": "Microsoft.Network/virtualNetworks", "apiVersion": "2023-05-01", "name": "vnet1",
               "resources": [
                 {"type": "subnets", "apiVersion": "2023-05-01", "name": "default", "dependsOn": ["vnet1"]},
                 {"type": "Microsoft.Network/virtualNetworks/subnets", "apiVersion": "2023-05-01",
                  "name": "vnet1/full"}]},
              {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01",
               "name": "[parameters('account')]",
               "resources": [{"type": "blobServices", "apiVersion": "2023-01-01", "name": "default",
                 "resources": [{"type": "containers", "apiVersion": "2023-01-01", "name": "it's"}]}]}]}
            """));

        assertEquals(List.of("Microsoft.Network/virtualNetworks:vnet1",
            "Microsoft.Network/virtualNetworks/subnets:vnet1/default",
            "Microsoft.Network/virtualNetworks/subnets:vnet1/full",
            "Microsoft.Storage/storageAccounts:[parameters('account')]",
            "Microsoft.Storage/storageAccounts/blobServices:[concat(parameters('account'), '/', 'default')]",
            "Microsoft.Storage/storageAccounts/blobServices/containers:"
                + "[concat(concat(parameters('account'), '/', 'default'), '/', 'it''s')]"),
            template.resources().stream().map(resource -> resource.definition().get("type").asText() + ":"
                + resource.definition().get("name").asText()).toList());
        assertEquals(List.of(0, 1, 2, 3, 4, 5), template.resources().stream().map(DeploymentTemplate.Resource::index).toList());
        assertFalse(template.resources().getFirst().definition().has("resources"));
        assertEquals("vnet1", template.resources().get(1).definition().get("dependsOn").get(0).asText());
    }

    @Test
    void joinedNamesKeepLiteralsLiteralAndEscapesUnescaped() {
        assertEquals("a/b", DeploymentTemplate.joined("a", "b"));
        assertEquals("[x]/b", DeploymentTemplate.joined("[[x]", "b"));
        assertEquals("[concat('a', '/', variables('b'))]", DeploymentTemplate.joined("a", "[variables('b')]"));
    }
}
