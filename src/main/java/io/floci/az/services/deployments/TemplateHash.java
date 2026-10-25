package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A template's hash, the {@code templateHash} a deployment reports and {@code calculateTemplateHash} answers. The two
 * agree for the same template whatever its whitespace or casing, which is what a client relies on: azd skips a
 * redeploy when the previous deployment's hash equals the one {@code calculateTemplateHash} gives for its template.
 *
 * <p>The template is minified and uppercased first, the form the spec's {@code CalculateTemplateHash} example shows
 * as {@code minifiedTemplate}, then hashed with 64-bit FNV-1a and written as an unsigned decimal, the form Azure's
 * hashes take. Azure does not publish its algorithm, so the value differs from Azure's.
 */
final class TemplateHash {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private TemplateHash() {
    }

    /** {@code template} as compact JSON, uppercased. */
    static String minified(JsonNode template) {
        return template.toString().toUpperCase(Locale.ROOT);
    }

    /** The hash of {@code template}'s minified form, as an unsigned decimal. */
    static String of(JsonNode template) {
        long hash = FNV_OFFSET;
        for (byte b : minified(template).getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= FNV_PRIME;
        }
        return Long.toUnsignedString(hash);
    }
}
