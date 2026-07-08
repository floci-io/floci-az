package io.floci.az.core.arm;

import io.floci.az.core.AzureRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ArmJson — request body parsing variants")
class ArmJsonTest {

    private static AzureRequest request(String body) {
        InputStream stream = body == null ? null
                : new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        return new AzureRequest("PUT", "acct", "arm", "path", null, stream, Map.of(), null, false);
    }

    @Test
    void lenientParsesValidJson() {
        assertEquals("eastus", ArmJson.parseBodyLenient(request("{\"location\":\"eastus\"}")).get("location"));
    }

    @Test
    void lenientDegradesToEmptyMapOnMissingOrMalformed() {
        assertEquals(Map.of(), ArmJson.parseBodyLenient(request(null)));
        assertEquals(Map.of(), ArmJson.parseBodyLenient(request("")));
        assertEquals(Map.of(), ArmJson.parseBodyLenient(request("{not json")));
    }

    @Test
    void mutableVariantReturnsMutableMapOnMiss() {
        Map<String, Object> parsed = ArmJson.parseBodyMutable(request("{not json"));
        parsed.put("k", "v");
        assertEquals("v", parsed.get("k"));

        // A null body stream must degrade the same way, not throw.
        Map<String, Object> fromNull = ArmJson.parseBodyMutable(request(null));
        fromNull.put("k", "v");
        assertEquals("v", fromNull.get("k"));
    }

    @Test
    void strictThrowsOnMalformedButToleratesEmpty() {
        assertEquals(Map.of(), ArmJson.parseBodyStrict(request(null)));
        assertEquals(Map.of(), ArmJson.parseBodyStrict(request("")));
        assertThrows(ArmJson.InvalidBodyException.class,
                () -> ArmJson.parseBodyStrict(request("{not json")));
    }

    @Test
    void castAndStringHelpers() {
        assertEquals(Map.of(), ArmJson.cast("not a map"));
        assertEquals(Map.of(), ArmJson.cast(null));
        assertTrue(ArmJson.cast(Map.of("a", 1)).containsKey("a"));
        assertEquals("x", ArmJson.string(Map.of("k", "x"), "k", "d"));
        assertEquals("d", ArmJson.string(Map.of("k", 42), "k", "d"));
    }
}
