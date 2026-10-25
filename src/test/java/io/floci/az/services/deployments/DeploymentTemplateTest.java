package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        "{\"resources\": [{\"type\": \"Microsoft.Storage/storageAccounts\", \"apiVersion\": \"1\", \"name\": \"a\","
            + " \"resources\": [{}]}]}",
        "{\"resources\": [{\"type\": \"Microsoft.Resources/deployments\", \"apiVersion\": \"1\", \"name\": \"m\"}]}",
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
}
