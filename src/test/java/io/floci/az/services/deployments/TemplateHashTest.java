package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TemplateHash: one hash for a template, whatever its whitespace or casing")
class TemplateHashTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theMinifiedTemplateIsCompactAndUppercase() throws Exception {
        assertEquals("{\"CONTENTVERSION\":\"1.0.0.0\",\"RESOURCES\":[]}",
            TemplateHash.minified(MAPPER.readTree("{ \"contentVersion\" : \"1.0.0.0\",\n  \"resources\": [] }")));
    }

    @Test
    void whitespaceAndCasingDoNotChangeTheHashButContentDoes() throws Exception {
        String hash = TemplateHash.of(MAPPER.readTree("{\"contentVersion\": \"1.0.0.0\", \"resources\": []}"));

        assertTrue(hash.matches("\\d{1,20}"), hash);
        assertEquals(hash, TemplateHash.of(MAPPER.readTree("{\n \"CONTENTVERSION\":\"1.0.0.0\",\"resources\":[ ]}")));
        assertNotEquals(hash, TemplateHash.of(MAPPER.readTree("{\"contentVersion\": \"1.0.0.1\", \"resources\": []}")));
    }
}
