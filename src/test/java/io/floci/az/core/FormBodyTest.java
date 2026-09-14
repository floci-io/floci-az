package io.floci.az.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Form parsing for the OAuth-style endpoints. The body is client input, so the parser never throws:
 * a caller reads a parameter it cannot use as absent and answers with its own error.
 */
@DisplayName("FormBody: form-encoded request bodies")
class FormBodyTest {

    private static Map<String, String> parse(String body) {
        return FormBody.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void decodesParameters() {
        Map<String, String> form = parse("grant_type=refresh_token&service=myreg.azurecr.io");
        assertEquals("refresh_token", form.get("grant_type"));
        assertEquals("myreg.azurecr.io", form.get("service"));
    }

    @Test
    void decodesPercentEscapesAndPlusAsSpace() {
        Map<String, String> form = parse("scope=repository%3Ateam%2Fapp%3Apull%2Cpush&note=a+b");
        assertEquals("repository:team/app:pull,push", form.get("scope"));
        assertEquals("a b", form.get("note"));
    }

    @Test
    void dropsAPairWithAMalformedEscapeInsteadOfThrowing() {
        // "%zz" is not a percent escape; URLDecoder throws on it. The pair is dropped so the
        // endpoint sees a missing parameter and answers its own 400 rather than a 500.
        Map<String, String> form = parse("grant_type=refresh_token&service=%zz");
        assertFalse(form.containsKey("service"));
        assertEquals("refresh_token", form.get("grant_type"));
    }

    @Test
    void dropsAPairWithATruncatedEscape() {
        assertFalse(parse("service=%").containsKey("service"));
        assertFalse(parse("service=abc%2").containsKey("service"));
        assertFalse(parse("%zz=value").containsKey("%zz"));
    }

    @Test
    void ignoresPairsWithNoSeparatorAndBlankBodies() {
        assertTrue(parse("").isEmpty());
        assertTrue(parse("   ").isEmpty());
        assertTrue(parse("novalue").isEmpty());
        assertEquals(Map.of("a", "1"), parse("novalue&a=1"));
    }

    @Test
    void treatsAnUnreadableOrAbsentBodyAsEmpty() {
        assertTrue(FormBody.parse(null).isEmpty());
        assertTrue(FormBody.parse(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("stream closed");
            }
        }).isEmpty());
    }
}
