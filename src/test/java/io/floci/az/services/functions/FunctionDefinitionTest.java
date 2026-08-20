package io.floci.az.services.functions;

import io.floci.az.services.functions.FunctionModels.FunctionDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FunctionDefinitionTest {

    @Test
    void emptyRoutePrefixRemainsUnprefixed() {
        FunctionDefinition definition = definition("");

        assertEquals("", definition.effectiveRoutePrefix());
    }

    @Test
    void customRoutePrefixIsPreserved() {
        FunctionDefinition definition = definition("functions");

        assertEquals("functions", definition.effectiveRoutePrefix());
    }

    @Test
    void missingRoutePrefixDefaultsToApi() {
        FunctionDefinition definition = definition(null);

        assertEquals("/api", definition.effectiveRoutePrefix());
    }

    private static FunctionDefinition definition(String routePrefix) {
        return new FunctionDefinition(
                "app", "hello", "account", "python", "Python|3.12", "function_app.hello",
                60, null, "/tmp/functions/hello", Instant.now(), true, routePrefix);
    }
}