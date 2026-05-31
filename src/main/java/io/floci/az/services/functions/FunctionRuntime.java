package io.floci.az.services.functions;

import java.util.Locale;
import java.util.Map;

public final class FunctionRuntime {

    private static final Map<String, String> DEFAULT_IMAGES = Map.of(
            "node",   "mcr.microsoft.com/azure-functions/node:4",
            "python", "mcr.microsoft.com/azure-functions/python:4",
            "java",   "mcr.microsoft.com/azure-functions/java:4",
            "dotnet", "mcr.microsoft.com/azure-functions/dotnet-isolated:4"
    );

    private FunctionRuntime() {}

    public static String resolveImage(String runtime, String linuxFxVersion) {
        String normalizedRuntime = normalizeRuntime(runtime);
        if (linuxFxVersion == null || linuxFxVersion.isBlank()) {
            return defaultImage(normalizedRuntime);
        }

        String[] parts = linuxFxVersion.split("\\|", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Invalid linuxFxVersion: " + linuxFxVersion
                    + ". Expected a value such as Python|3.12.");
        }

        String stack = normalizeRuntime(parts[0]);
        String version = parts[1].trim();
        if (!stack.equals(normalizedRuntime)) {
            throw new IllegalArgumentException("linuxFxVersion stack '" + parts[0]
                    + "' does not match runtime '" + runtime + "'.");
        }

        return switch (normalizedRuntime) {
            case "python" -> "mcr.microsoft.com/azure-functions/python:4-python" + version;
            case "node"   -> "mcr.microsoft.com/azure-functions/node:4-node" + version;
            case "java"   -> "mcr.microsoft.com/azure-functions/java:4-java" + version;
            default       -> defaultImage(normalizedRuntime);
        };
    }

    public static String runtimeFromLinuxFxVersion(String linuxFxVersion) {
        if (linuxFxVersion == null || linuxFxVersion.isBlank()) {
            return null;
        }
        String[] parts = linuxFxVersion.split("\\|", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Invalid linuxFxVersion: " + linuxFxVersion
                    + ". Expected a value such as Python|3.12.");
        }
        return normalizeRuntime(parts[0]);
    }

    private static String normalizeRuntime(String runtime) {
        String normalized = runtime == null ? "" : runtime.trim().toLowerCase(Locale.ROOT);
        return "dotnet-isolated".equals(normalized) ? "dotnet" : normalized;
    }

    private static String defaultImage(String runtime) {
        String image = DEFAULT_IMAGES.get(runtime);
        if (image == null) {
            throw new IllegalArgumentException("Unsupported runtime: " + runtime
                    + ". Supported: " + DEFAULT_IMAGES.keySet());
        }
        return image;
    }
}
