package io.floci.az.services.functions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FunctionRuntimeTest {

    @Test
    void pythonLinuxFxVersionSelectsVersionedImage() {
        assertEquals("mcr.microsoft.com/azure-functions/python:4-python3.12",
                FunctionRuntime.resolveImage("python", "Python|3.12"));
    }

    @Test
    void missingLinuxFxVersionKeepsDefaultImage() {
        assertEquals("mcr.microsoft.com/azure-functions/python:4",
                FunctionRuntime.resolveImage("python", null));
    }

    @Test
    void linuxFxVersionStackMustMatchRuntime() {
        assertThrows(IllegalArgumentException.class,
                () -> FunctionRuntime.resolveImage("python", "Node|22"));
    }

    @Test
    void runtimeCanBeDerivedFromLinuxFxVersion() {
        assertEquals("python", FunctionRuntime.runtimeFromLinuxFxVersion("Python|3.12"));
    }
}
