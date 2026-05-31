package io.floci.az.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FunctionsLinuxFxVersionCompatibilityTest {

    private static final String BASE =
            System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");
    private static final String ACCOUNT = EmulatorConfig.ACCOUNT;
    private static final String FUNCTIONS_BASE = BASE + "/" + ACCOUNT + "-functions";
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String RESOURCE_GROUP = "linuxfx-rg";
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void checkEmulator() {
        EmulatorConfig.assumeEmulatorRunning();
    }

    @Test
    void adminApiPersistsPythonLinuxFxVersion() throws Exception {
        String appName = "linuxfx-admin-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> create = put(FUNCTIONS_BASE + "/admin/apps/" + appName,
                "{\"runtime\":\"python\",\"linuxFxVersion\":\"Python|3.12\"}");
        assertEquals(201, create.statusCode(), create.body());
        assertEquals("Python|3.12", mapper.readTree(create.body()).get("linuxFxVersion").asText());
    }

    @Test
    void armSiteConfigPersistsAndBridgesPythonLinuxFxVersion() throws Exception {
        String appName = "linuxfx-arm-" + UUID.randomUUID().toString().substring(0, 8);
        String sitePath = BASE + "/subscriptions/" + SUBSCRIPTION
                + "/resourceGroups/" + RESOURCE_GROUP
                + "/providers/Microsoft.Web/sites/" + appName;
        String siteUrl = sitePath + "?api-version=2024-11-01";

        HttpResponse<String> create = put(siteUrl, """
                {"location":"eastus","properties":{"siteConfig":{"linuxFxVersion":"Python|3.12"}}}
                """);
        assertEquals(200, create.statusCode(), create.body());

        HttpResponse<String> config = get(sitePath + "/config/web?api-version=2024-11-01");
        assertEquals(200, config.statusCode(), config.body());
        JsonNode properties = mapper.readTree(config.body()).get("properties");
        assertEquals("Python|3.12", properties.get("linuxFxVersion").asText());

        HttpResponse<String> app = get(FUNCTIONS_BASE + "/admin/apps/" + appName);
        assertEquals(200, app.statusCode(), app.body());
        assertEquals("python", mapper.readTree(app.body()).get("runtime").asText());
        assertEquals("Python|3.12", mapper.readTree(app.body()).get("linuxFxVersion").asText());
    }

    @Test
    void armRejectsMalformedLinuxFxVersion() throws Exception {
        String appName = "linuxfx-invalid-" + UUID.randomUUID().toString().substring(0, 8);
        String siteUrl = BASE + "/subscriptions/" + SUBSCRIPTION
                + "/resourceGroups/" + RESOURCE_GROUP
                + "/providers/Microsoft.Web/sites/" + appName
                + "?api-version=2024-11-01";

        HttpResponse<String> create = put(siteUrl, """
                {"properties":{"siteConfig":{"linuxFxVersion":"python-3.12"}}}
                """);
        assertEquals(400, create.statusCode(), create.body());
    }

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
}
