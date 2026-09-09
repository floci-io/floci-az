package io.floci.az.core.nativeimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that every class named in {@code reflect-config.json} actually exists.
 *
 * <p>GraalVM ignores an entry whose class it cannot find, so a typo or a class renamed by a
 * dependency upgrade costs nothing at build time and silently drops the registration. The failure
 * only appears later, in a native binary, as a reflective call returning nothing or throwing.
 * Resolving the names here turns that into a unit-test failure.
 *
 * <p>Names are resolved with {@code initialize = false}: this must not run static initialisers
 * for a few hundred third-party classes, only confirm the names resolve.
 */
@DisplayName("reflect-config.json — every registered class resolves")
class ReflectConfigTest {

    private static final String RESOURCE = "META-INF/native-image/io.floci/az/reflect-config.json";

    @Test
    void everyRegisteredClassExists() throws Exception {
        List<String> names = readRegisteredNames();
        assertFalse(names.isEmpty(), "reflect-config.json should not be empty");

        List<String> unresolvable = new ArrayList<>();
        for (String name : names) {
            try {
                Class.forName(name, false, getClass().getClassLoader());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                unresolvable.add(name);
            }
        }

        assertTrue(unresolvable.isEmpty(),
            "reflect-config.json registers classes that do not exist, so GraalVM silently drops them: "
                + unresolvable);
    }

    private List<String> readRegisteredNames() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "missing resource: " + RESOURCE);
            JsonNode root = new ObjectMapper().readTree(in);
            List<String> names = new ArrayList<>();
            for (JsonNode entry : root) {
                JsonNode name = entry.get("name");
                if (name != null && !name.asText().isBlank()) {
                    names.add(name.asText());
                }
            }
            return names;
        }
    }
}
