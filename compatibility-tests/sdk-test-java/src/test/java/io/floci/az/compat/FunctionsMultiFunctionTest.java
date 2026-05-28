package io.floci.az.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that multiple functions deployed to the same app share a single container.
 *
 * Management tests (create/deploy/list) run without Docker.
 * Invocation and container-count tests require Docker and are skipped when unavailable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FunctionsMultiFunctionTest {

    private static final String BASE =
            System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");
    private static final String ACCOUNT = EmulatorConfig.ACCOUNT;
    private static final String FUNCTIONS_BASE = BASE + "/" + ACCOUNT + "-functions";

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String APP_NAME     = "multiapp-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String FUNC_GREET   = "greet";
    private static final String FUNC_FAREWELL = "farewell";

    private static boolean dockerAvailable = false;

    @BeforeAll
    static void setUp() {
        EmulatorConfig.assumeEmulatorRunning();
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            dockerAvailable = p.waitFor() == 0;
        } catch (Exception e) {
            dockerAvailable = false;
        }
    }

    // ── App and function setup ────────────────────────────────────────────────

    @Test @Order(1)
    void createApp_returns201() throws Exception {
        HttpResponse<String> resp = put(FUNCTIONS_BASE + "/admin/apps/" + APP_NAME,
                "{\"runtime\":\"node\"}");
        assertEquals(201, resp.statusCode(), "createApp: " + resp.body());
        assertEquals(APP_NAME, mapper.readTree(resp.body()).get("name").asText());
    }

    @Test @Order(2)
    void deployGreetFunction_returns201() throws Exception {
        String body = buildDeployBody("index.handler", 60, buildFunctionZip("Hello, World!"));
        HttpResponse<String> resp = put(
                FUNCTIONS_BASE + "/admin/apps/" + APP_NAME + "/functions/" + FUNC_GREET, body);
        assertEquals(201, resp.statusCode(), "deployGreet: " + resp.body());
        assertEquals("Ready", mapper.readTree(resp.body()).get("status").asText());
    }

    @Test @Order(3)
    void deployFarewellFunction_returns201() throws Exception {
        String body = buildDeployBody("index.handler", 60, buildFunctionZip("Goodbye, World!"));
        HttpResponse<String> resp = put(
                FUNCTIONS_BASE + "/admin/apps/" + APP_NAME + "/functions/" + FUNC_FAREWELL, body);
        assertEquals(201, resp.statusCode(), "deployFarewell: " + resp.body());
        assertEquals("Ready", mapper.readTree(resp.body()).get("status").asText());
    }

    @Test @Order(4)
    void listFunctions_containsBothFunctions() throws Exception {
        HttpResponse<String> resp = get(
                FUNCTIONS_BASE + "/admin/apps/" + APP_NAME + "/functions");
        assertEquals(200, resp.statusCode());
        JsonNode value = mapper.readTree(resp.body()).get("value");
        assertTrue(value.isArray(), "Expected array in response");
        assertEquals(2, value.size(), "Expected exactly 2 functions");
        List<String> names = new ArrayList<>();
        for (JsonNode fn : value) {
            names.add(fn.get("name").asText());
        }
        assertTrue(names.contains(FUNC_GREET),    "greet missing from list: " + names);
        assertTrue(names.contains(FUNC_FAREWELL),  "farewell missing from list: " + names);
    }

    // ── Invocation (Docker required) ──────────────────────────────────────────

    @Test @Order(5)
    void invokeGreetFunction_returns200() throws Exception {
        assumeTrue(dockerAvailable, "Docker not available — skipping invocation test");
        HttpResponse<String> resp = get(
                FUNCTIONS_BASE + "/api/" + APP_NAME + "/" + FUNC_GREET);
        assertEquals(200, resp.statusCode(), "invoke greet: " + resp.body());
        assertTrue(resp.body().contains("Hello"), "Unexpected body: " + resp.body());
    }

    @Test @Order(6)
    void invokeFarewellFunction_returns200() throws Exception {
        assumeTrue(dockerAvailable, "Docker not available — skipping invocation test");
        HttpResponse<String> resp = get(
                FUNCTIONS_BASE + "/api/" + APP_NAME + "/" + FUNC_FAREWELL);
        assertEquals(200, resp.statusCode(), "invoke farewell: " + resp.body());
        assertTrue(resp.body().contains("Goodbye"), "Unexpected body: " + resp.body());
    }

    // ── Single container per app (Docker required) ────────────────────────────

    @Test @Order(7)
    void onlyOneContainerRunningForApp() throws Exception {
        assumeTrue(dockerAvailable, "Docker not available — skipping container-count test");
        // Both functions were invoked above; the warm pool should hold exactly one container.
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "ps", "--filter", "name=floci-az-fn-" + APP_NAME, "--format", "{{.ID}}");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();

        long count = output.isBlank() ? 0L : output.lines().filter(l -> !l.isBlank()).count();
        assertEquals(1L, count,
                "Expected exactly 1 container for app '" + APP_NAME + "', found " + count
                        + ".\nRunning containers:\n" + output);
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    @Test @Order(8)
    void deleteApp_returns204() throws Exception {
        HttpResponse<String> resp = delete(FUNCTIONS_BASE + "/admin/apps/" + APP_NAME);
        assertEquals(204, resp.statusCode());
    }

    @Test @Order(9)
    void getApp_afterDelete_returns404() throws Exception {
        HttpResponse<String> resp = get(FUNCTIONS_BASE + "/admin/apps/" + APP_NAME);
        assertEquals(404, resp.statusCode());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(String url, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .PUT(HttpRequest.BodyPublishers.ofString(json))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String buildDeployBody(String handler, int timeoutSeconds, String zipBase64) {
        return String.format(
                "{\"handler\":\"%s\",\"timeoutSeconds\":%d,\"zipBase64\":\"%s\"}",
                handler, timeoutSeconds, zipBase64);
    }

    /**
     * Builds a flat Azure Functions Node.js ZIP (function.json + index.js only).
     * ContainerLauncher injects files at wwwroot/{funcName}/ and manages host.json
     * separately, so ZIPs must not include host.json or a function-name subdirectory.
     */
    private static String buildFunctionZip(String responseMessage) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            addZipEntry(zos, "function.json", """
                    {
                      "bindings": [
                        {"authLevel":"anonymous","type":"httpTrigger","direction":"in",
                         "name":"req","methods":["get","post"]},
                        {"type":"http","direction":"out","name":"res"}
                      ]
                    }
                    """);
            addZipEntry(zos, "index.js",
                    "module.exports = async function(context, req) {\n"
                    + "  context.res = { status: 200, body: \"" + responseMessage + "\" };\n"
                    + "};\n");
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static void addZipEntry(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
