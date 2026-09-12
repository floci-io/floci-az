package io.floci.az.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses {@code application/x-www-form-urlencoded} request bodies. OAuth-style endpoints
 * (Entra's token endpoint, the ACR token exchange) are the only form-encoded surfaces in the
 * emulator, and both read the body as a flat map of decoded parameters.
 */
public final class FormBody {

    private FormBody() {
    }

    /**
     * Decoded form parameters; an unreadable or empty body yields an empty map.
     *
     * <p>A pair that will not decode is dropped rather than thrown: a malformed percent escape is
     * client input, and the endpoints answer a parameter that did not arrive with their own
     * documented error. Letting {@link URLDecoder} throw here would escape the handler instead,
     * because nothing on the dispatch path catches it, and a 500 would replace that error.</p>
     */
    public static Map<String, String> parse(InputStream body) {
        Map<String, String> result = new HashMap<>();
        byte[] bytes;
        try {
            bytes = body == null ? new byte[0] : body.readAllBytes();
        } catch (IOException e) {
            return result;
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            return result;
        }
        for (String pair : content.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            try {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                result.put(key, value);
            } catch (IllegalArgumentException e) {
                // A malformed escape such as "%zz"; the parameter is treated as absent.
            }
        }
        return result;
    }
}
