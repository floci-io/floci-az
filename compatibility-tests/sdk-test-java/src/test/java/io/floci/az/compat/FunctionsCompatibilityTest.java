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
import java.util.Base64;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Azure Functions management API compatibility tests.
 *
 * Management tests (create/deploy/get/list/delete) run without Docker.
 * Invocation tests require Docker and are skipped when it is unavailable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FunctionsCompatibilityTest {

    private static final String BASE =
            System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");
    private static final String ACCOUNT = EmulatorConfig.ACCOUNT;
    private static final String FUNCTIONS_BASE = BASE + "/" + ACCOUNT + "-functions";

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String APP_NAME  = "testapp-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String FUNC_NAME = "hello";

    private static boolean dockerAvailable = false;

    @BeforeAll
    static void checkDocker() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            dockerAvailable = p.waitFor() == 0;
        } catch (Exception e) {
            dockerAvailable = false;
        }
    }

    // ── App lifecycle ─────────────────────────────────────────────────────────

    @Test @Order(1)
    void createApp_returns201() throws Exception {
        String body = """
                {"runtime":"node","environment":{"MY_VAR":"hello"}}
                """;
        HttpResponse<String> resp = put(FUNCTIONS_BASE + "/admin/apps/" + APP_NAME, body);
        assertEquals(201, resp.statusCode(), "createApp: " + resp.body());

        JsonNode json = mapper.readTree(resp.body());
        assertEquals(APP_NAME, json.get("name").asText());
        assertEquals("node",   json.get("runtime").asText());
        assertEquals("Running", json.get("status").asText());
    }

    @Test @Order(2)
    void getApp_returns200() throws Exception {
        HttpResponse<String> resp = get(FUNCTIONS_BASE + "/admin/apps/" + APP_NAME);
        assertEquals(200, resp.statusCode());
        assertEquals(APP_NAME, mapper.readTree(resp.body()).get("name").asText());
    }

    @Test @Order(3)
    void createApp_duplicate_returns201_overwrite() throws Exception {
        // Re-PUT an existing app is idempotent (upsert)
        String body = """
                {"runtime":"node"}
                """;
        HttpResponse<String> resp = put(FUNCTIONS_BASE + "/admin/apps/" + APP_NAME, body);
        assertEquals(201, resp.statusCode());
    }

    @Test @Order(4)
    void createApp_missingRuntime_returns400() throws Exception {
        HttpResponse<String> resp = put(FUNCTIONS_BASE + "/admin/apps/bad-app", "{}");
        assertEquals(400, resp.statusCode());
    }

    // ── Function deployment ───────────────────────────────────────────────────

    @Test @Order(5)
    void deployFunction_returns201() throws Exception {
        String body = buildDeployBody("index.handler", 60, buildNodeZipBase64());
        HttpResponse<String> resp = put(
                FUNCTIONS_BASE + "/admin/apps/" + APP_NAME + "/functions/" + FUNC_NAME, body);
        assertEquals(201, resp.statusCode(), "deployFunction: " + resp.body());

        JsonNode json = mapper.readTree(resp.body());
        assertEquals(FUNC_NAME, json.get("name").asText());
        assertEquals(APP_NAME,  json.get("appName").asText());
        assertEquals("node",    json.get("runtime").asText());
        assertEquals("Ready",   json.get("status").asText());
    }

    @Test @Order(6)
    void getFunction_returns200() throws Exception {
        HttpResponse<String> resp = get(
                FUNCTIONS_BASE + "/admin/apps/" + APP_NAME + "/functions/" + FUNC_NAME);
        assertEquals(200, resp.statusCode());
        JsonNode json = mapper.readTree(resp.body());
        assertEquals(FUNC_NAME, json.get("name").asText());
        assertNotNull(json.get("invokeUrl"));
    }

    @Test @Order(7)
    void listFunctions_containsDeployedFunction() throws Exception {
        HttpResponse<String> resp = get(
                FUNCTIONS_BASE + "/admin/apps/" + APP_NAME + "/functions");
        assertEquals(200, resp.statusCode());
        JsonNode value = mapper.readTree(resp.body()).get("value");
        assertTrue(value.isArray() && value.size() >= 1, "Expected at least 1 function in list");
        boolean found = false;
        for (JsonNode fn : value) {
            if (FUNC_NAME.equals(fn.get("name").asText())) { found = true; break; }
        }
        assertTrue(found, "Deployed function not found in list");
    }

    @Test @Order(8)
    void getFunction_notFound_returns404() throws Exception {
        HttpResponse<String> resp = get(
                FUNCTIONS_BASE + "/admin/apps/" + APP_NAME + "/functions/no-such-fn");
        assertEquals(404, resp.statusCode());
    }

    // ── Invocation (requires Docker) ──────────────────────────────────────────

    @Test @Order(9)
    void invokeHttpTrigger_GET_returns200() throws Exception {
        assumeTrue(dockerAvailable, "Docker not available — skipping invocation test");
        HttpResponse<String> resp = get(
                FUNCTIONS_BASE + "/api/" + APP_NAME + "/" + FUNC_NAME + "?msg=world");
        assertEquals(200, resp.statusCode(), "invoke GET: " + resp.body());
        assertTrue(resp.body().contains("world") || resp.body().contains("Hello"),
                "Unexpected body: " + resp.body());
    }

    @Test @Order(10)
    void invokeHttpTrigger_POST_returns200() throws Exception {
        assumeTrue(dockerAvailable, "Docker not available — skipping invocation test");
        HttpResponse<String> resp = post(
                FUNCTIONS_BASE + "/api/" + APP_NAME + "/" + FUNC_NAME,
                "{\"name\":\"floci-az\"}",
                "application/json");
        assertEquals(200, resp.statusCode(), "invoke POST: " + resp.body());
    }

    @Test @Order(11)
    void invoke_noCode_returns409() throws Exception {
        // Deploy a function with no code
        String noCodeApp  = "nocode-" + UUID.randomUUID().toString().substring(0, 6);
        String noCodeFunc = "fn1";
        put(FUNCTIONS_BASE + "/admin/apps/" + noCodeApp, "{\"runtime\":\"node\"}");
        // Deploy function with empty zipBase64
        String body = "{\"handler\":\"index.handler\",\"timeoutSeconds\":30}";
        put(FUNCTIONS_BASE + "/admin/apps/" + noCodeApp + "/functions/" + noCodeFunc, body);

        HttpResponse<String> resp = get(
                FUNCTIONS_BASE + "/api/" + noCodeApp + "/" + noCodeFunc);
        assertEquals(409, resp.statusCode());

        // Cleanup
        delete(FUNCTIONS_BASE + "/admin/apps/" + noCodeApp);
    }

    @Test @Order(12)
    void invoke_missingFunction_returns404() throws Exception {
        HttpResponse<String> resp = get(
                FUNCTIONS_BASE + "/api/" + APP_NAME + "/no-such-fn");
        assertEquals(404, resp.statusCode());
    }

    // ── Deletion ──────────────────────────────────────────────────────────────

    @Test @Order(13)
    void deleteFunction_returns204() throws Exception {
        HttpResponse<String> resp = delete(
                FUNCTIONS_BASE + "/admin/apps/" + APP_NAME + "/functions/" + FUNC_NAME);
        assertEquals(204, resp.statusCode());
    }

    @Test @Order(14)
    void getFunction_afterDelete_returns404() throws Exception {
        HttpResponse<String> resp = get(
                FUNCTIONS_BASE + "/admin/apps/" + APP_NAME + "/functions/" + FUNC_NAME);
        assertEquals(404, resp.statusCode());
    }

    @Test @Order(15)
    void deleteApp_returns204() throws Exception {
        HttpResponse<String> resp = delete(FUNCTIONS_BASE + "/admin/apps/" + APP_NAME);
        assertEquals(204, resp.statusCode());
    }

    @Test @Order(16)
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

    private static HttpResponse<String> post(String url, String body, String contentType) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", contentType)
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
     * Builds a minimal Azure Functions v3 Node.js ZIP in-memory and returns it as base64.
     * Structure:
     *   host.json
     *   hello/function.json   (HTTP trigger)
     *   hello/index.js        (handler)
     */
    private static String buildNodeZipBase64() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            addZipEntry(zos, "host.json", "{\"version\":\"2.0\"}");
            addZipEntry(zos, "hello/function.json", """
                    {
                      "bindings": [
                        {"authLevel":"anonymous","type":"httpTrigger","direction":"in",
                         "name":"req","methods":["get","post"]},
                        {"type":"http","direction":"out","name":"res"}
                      ]
                    }
                    """);
            addZipEntry(zos, "hello/index.js", """
                    module.exports = async function(context, req) {
                        const msg = (req.query && req.query.msg) || "world";
                        context.res = { status: 200, body: "Hello, " + msg + "!" };
                    };
                    """);
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static void addZipEntry(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
