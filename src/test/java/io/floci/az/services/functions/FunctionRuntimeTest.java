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
    void dotnetIsolatedLinuxFxVersionSelectsVersionedImage() {
        assertEquals("mcr.microsoft.com/azure-functions/dotnet-isolated:4-dotnet-isolated10.0",
                FunctionRuntime.resolveImage("dotnet", "DOTNET-ISOLATED|10.0"));
        assertEquals("mcr.microsoft.com/azure-functions/dotnet-isolated:4-dotnet-isolated8.0",
                FunctionRuntime.resolveImage("dotnet-isolated", "DOTNET-ISOLATED|8.0"));
    }

    @Test
    void dotnetWithoutVersionKeepsDefaultImage() {
        assertEquals("mcr.microsoft.com/azure-functions/dotnet-isolated:4",
                FunctionRuntime.resolveImage("dotnet", null));
    }

    @Test
    void inProcessDotnetStackKeepsDefaultImage() {
        assertEquals("mcr.microsoft.com/azure-functions/dotnet-isolated:4",
                FunctionRuntime.resolveImage("dotnet", "DOTNET|8.0"));
    }

    @Test
    void runtimeCanBeDerivedFromLinuxFxVersion() {
        assertEquals("python", FunctionRuntime.runtimeFromLinuxFxVersion("Python|3.12"));
    }
}
