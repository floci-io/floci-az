package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("WhatIf: the template's properties compared with the current resource")
class WhatIfTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static ArrayNode diff(String current, String desired) throws Exception {
        ArrayNode delta = MAPPER.createArrayNode();
        WhatIf.diff("", json(current), json(desired), delta);
        return delta;
    }

    @Test
    void onlyTheTemplatesPropertiesAreCompared() throws Exception {
        ArrayNode delta = diff("""
            {"location": "eastus", "tags": {"a": "1"},
             "properties": {"provisioningState": "Succeeded", "addressSpace": {"addressPrefixes": ["10.0.0.0/16"]},
                            "count": 3}}
            """, """
            {"Location": "East US", "tags": {"a": "1", "b": "2"},
             "properties": {"addressSpace": {"addressPrefixes": ["10.1.0.0/16"]}, "count": 3.0}}
            """);

        assertEquals(2, delta.size(), delta.toString());
        assertEquals("tags.b", delta.get(0).path("path").asText());
        assertEquals("Create", delta.get(0).path("propertyChangeType").asText());
        assertEquals("properties.addressSpace.addressPrefixes", delta.get(1).path("path").asText());
        assertEquals("Modify", delta.get(1).path("propertyChangeType").asText());
        assertEquals("[\"10.0.0.0/16\"]", delta.get(1).path("before").toString());
    }

    @Test
    void anIdenticalResourceHasNoDelta() throws Exception {
        assertEquals(0, diff("{\"sku\": {\"name\": \"Standard_LRS\"}, \"x\": 1}", "{\"sku\": {\"name\": \"Standard_LRS\"}}").size());
    }

    @Test
    void afterIsTheCurrentResourceWithTheTemplateApplied() throws Exception {
        ObjectNode after = WhatIf.applied((ObjectNode) json("{\"id\": \"x\", \"Tags\": {\"a\": \"1\"}, \"properties\": {\"keep\": 1, \"set\": 1}}"),
            (ObjectNode) json("{\"tags\": {\"b\": \"2\"}, \"properties\": {\"set\": 2}}"));

        assertEquals("{\"id\":\"x\",\"Tags\":{\"a\":\"1\",\"b\":\"2\"},\"properties\":{\"keep\":1,\"set\":2}}", after.toString());
    }
}
