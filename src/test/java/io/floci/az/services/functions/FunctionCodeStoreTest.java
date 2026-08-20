package io.floci.az.services.functions;

import io.floci.az.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FunctionCodeStoreTest {

    @Test
    void detectsPythonV2RootLayoutAndEmptyRoutePrefix() throws Exception {
        Path root = Files.createTempDirectory("function-code-");
        try {
            Files.writeString(root.resolve("function_app.py"), "import azure.functions");
            Files.writeString(root.resolve("host.json"), """
                    {"version":"2.0","extensions":{"http":{"routePrefix":""}}}
                    """);

            FunctionCodeStore store = newStore(root);

            assertTrue(store.isPackageRootLayout(root));
            assertEquals("", store.routePrefix(root));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void defaultsToV1LayoutWhenFunctionAppIsAbsent() throws Exception {
        Path root = Files.createTempDirectory("function-code-");
        try {
            Files.writeString(root.resolve("function.json"), "{}");

            FunctionCodeStore store = newStore(root);

            assertFalse(store.isPackageRootLayout(root));
            assertNull(store.routePrefix(root));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void storesPythonV2ZipWithRootFilesAndPackagesIntact() throws Exception {
        Path basePath = Files.createTempDirectory("function-store-");
        try {
            FunctionCodeStore store = newStore(basePath);
            Path stored = store.storeCode("account", "app", "hello", pythonV2Zip());

            assertTrue(Files.isRegularFile(stored.resolve("function_app.py")));
            assertTrue(Files.isRegularFile(stored.resolve("host.json")));
            assertTrue(Files.isRegularFile(stored.resolve(
                    ".python_packages/lib/site-packages/example.py")));
            assertTrue(store.isPackageRootLayout(stored));
            assertEquals("", store.routePrefix(stored));
        } finally {
            deleteRecursively(basePath);
        }
    }

    @Test
    void detectsDecoratedRouteAndNormalizesLegacyDefinition() throws Exception {
        Path basePath = Files.createTempDirectory("function-store-");
        try {
            FunctionCodeStore store = newStore(basePath);
            Path stored = store.storeCode("account", "app", "hello", decoratedPythonV2Zip());
            FunctionModels.FunctionDefinition legacy = new FunctionModels.FunctionDefinition(
                    "app", "hello", "account", "python", "Python|3.12", "function_app.greet",
                    60, null, stored.toString(), java.time.Instant.now(), false, null, null);

            FunctionModels.FunctionDefinition normalized = store.normalize(legacy);

            assertTrue(normalized.packageRootLayout());
            assertEquals("", normalized.routePrefix());
            assertEquals("welcome", normalized.functionRoute());
        } finally {
            deleteRecursively(basePath);
        }
    }

    private static byte[] pythonV2Zip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            addEntry(zip, "function_app.py", "import azure.functions\n");
            addEntry(zip, "host.json",
                    "{\"version\":\"2.0\",\"extensions\":{\"http\":{\"routePrefix\":\"\"}}}");
            addEntry(zip, ".python_packages/lib/site-packages/example.py", "VALUE = 1\n");
        }
        return bytes.toByteArray();
    }

    private static byte[] decoratedPythonV2Zip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            addEntry(zip, "function_app.py", """
                    import azure.functions as func
                    app = func.FunctionApp()
                    @app.route(route="welcome")
                    def greet(req: func.HttpRequest) -> func.HttpResponse:
                        return func.HttpResponse("ok")
                    """);
            addEntry(zip, "host.json",
                    "{\"version\":\"2.0\",\"extensions\":{\"http\":{\"routePrefix\":\"\"}}}");
        }
        return bytes.toByteArray();
    }

    private static void addEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static FunctionCodeStore newStore(Path codePath) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.FunctionsConfig functions = mock(EmulatorConfig.FunctionsConfig.class);
        when(config.services()).thenReturn(services);
        when(services.functions()).thenReturn(functions);
        when(functions.codePath()).thenReturn(codePath.toString());
        return new FunctionCodeStore(config, new FunctionZipExtractor());
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}