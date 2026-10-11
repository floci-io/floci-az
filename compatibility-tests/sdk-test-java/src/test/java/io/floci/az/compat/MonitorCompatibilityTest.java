package io.floci.az.compat;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.monitor.ingestion.LogsIngestionClient;
import com.azure.monitor.ingestion.LogsIngestionClientBuilder;
import com.azure.monitor.query.logs.LogsQueryClient;
import com.azure.monitor.query.logs.LogsQueryClientBuilder;
import com.azure.monitor.query.logs.models.LogsColumnType;
import com.azure.monitor.query.logs.models.LogsQueryResult;
import com.azure.monitor.query.logs.models.LogsQueryResultStatus;
import com.azure.monitor.query.logs.models.LogsQueryTimeInterval;
import com.azure.monitor.query.logs.models.LogsTable;
import com.azure.monitor.query.logs.models.LogsTableCell;
import com.azure.monitor.query.logs.models.LogsTableRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility test for Azure Monitor Logs: Log Analytics workspaces, Data Collection Endpoints and
 * Rules (ARM, raw HTTP), the Logs Ingestion API and the Log Analytics query API.
 *
 * <p>Queries go through the real {@code azure-monitor-query-logs} {@link LogsQueryClient}. Both data-plane
 * clients authenticate with {@code BearerTokenAuthenticationPolicy}, which refuses a non-https endpoint,
 * so they are given the https form of the emulator URL and {@link EmulatorConfig.ForceHttpPolicy}
 * rewrites each request back to http, as the App Configuration and Key Vault tests do.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Azure Monitor Logs Java SDK Compatibility")
class MonitorCompatibilityTest {

    private static final String BASE = EmulatorConfig.httpBase();
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String RG = "monitor-rg-" + SUFFIX;
    private static final String WORKSPACE = "ws-" + SUFFIX;
    private static final String DCE = "dce-" + SUFFIX;
    private static final String DCR = "dcr-" + SUFFIX;
    private static final String STREAM = "Custom-CompatLogs_CL";
    private static final String TABLE = "CompatLogs_CL";

    private static final String WORKSPACE_API = "2023-09-01";
    private static final String DCR_API = "2023-03-11";
    private static final String INGESTION_API = "2023-01-01";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TokenCredential FAKE_CREDENTIAL =
            request -> Mono.just(new AccessToken("fake-token", OffsetDateTime.now().plusHours(1)));

    private static String customerId;
    private static String immutableId;
    private static String ingestionEndpoint;
    private static LogsQueryClient queryClient;

    @BeforeAll
    static void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        queryClient = new LogsQueryClientBuilder()
                .endpoint(httpsBase())
                .credential(FAKE_CREDENTIAL)
                .addPolicy(new EmulatorConfig.ForceHttpPolicy())
                .buildClient();
    }

    @Test
    @Order(1)
    void createWorkspaceDataCollectionEndpointAndRule() throws Exception {
        JsonNode workspace = putJson(armUrl("Microsoft.OperationalInsights/workspaces/" + WORKSPACE, WORKSPACE_API),
                "{\"location\":\"eastus\",\"properties\":{}}");
        assertEquals(WORKSPACE, workspace.get("name").asText());
        assertEquals("Microsoft.OperationalInsights/workspaces", workspace.get("type").asText());
        customerId = workspace.get("properties").get("customerId").asText();
        UUID.fromString(customerId);

        JsonNode dce = putJson(armUrl("Microsoft.Insights/dataCollectionEndpoints/" + DCE, DCR_API),
                "{\"location\":\"eastus\",\"properties\":{}}");
        ingestionEndpoint = dce.get("properties").get("logsIngestion").get("endpoint").asText();
        assertTrue(ingestionEndpoint.startsWith("http"), "logsIngestion.endpoint: " + ingestionEndpoint);

        String workspaceId = "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.OperationalInsights/workspaces/" + WORKSPACE;
        JsonNode dcr = putJson(armUrl("Microsoft.Insights/dataCollectionRules/" + DCR, DCR_API),
                "{\"location\":\"eastus\",\"properties\":{\"destinations\":{\"logAnalytics\":"
                        + "[{\"name\":\"ws\",\"workspaceResourceId\":\"" + workspaceId + "\"}]}}}");
        immutableId = dcr.get("properties").get("immutableId").asText();
        assertTrue(immutableId.startsWith("dcr-"), "immutableId: " + immutableId);
    }

    @Test
    @Order(2)
    void ingestThroughTheLogsIngestionApi() throws Exception {
        Instant now = Instant.now();
        String body = MAPPER.writeValueAsString(List.of(
                Map.of("TimeGenerated", now.minusSeconds(60).toString(), "Level", "INFO", "Message", "started"),
                Map.of("TimeGenerated", now.minusSeconds(30).toString(), "Level", "ERROR", "Message", "boom"),
                Map.of("TimeGenerated", now.minus(Duration.ofDays(3)).toString(), "Level", "ERROR",
                        "Message", "old")));
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/dataCollectionRules/" + immutableId
                        + "/streams/" + STREAM + "?api-version=" + INGESTION_API))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode(), response.body());
    }

    @Test
    @Order(3)
    void queryWorkspaceReturnsTheIngestedRows() {
        LogsQueryResult result = queryClient.queryWorkspace(customerId,
                TABLE + " | where Level == 'ERROR' | project TimeGenerated, Message", LogsQueryTimeInterval.ALL);

        assertEquals(LogsQueryResultStatus.SUCCESS, result.getQueryResultStatus());
        LogsTable table = result.getTable();
        // LogsTable.getColumns() stays empty in azure-monitor-query-logs 1.0.x (the client never fills it),
        // so the column names and types are read from the cells, which carry them.
        List<String> messages = new ArrayList<>();
        for (LogsTableRow row : table.getRows()) {
            List<String> columns = new ArrayList<>();
            for (LogsTableCell cell : row.getRow()) {
                columns.add(cell.getColumnName());
            }
            assertEquals(List.of("TimeGenerated", "Message"), columns);
            LogsTableCell time = row.getColumnValue("TimeGenerated").orElseThrow();
            assertEquals(LogsColumnType.DATETIME, time.getColumnType());
            assertTrue(time.getValueAsDateTime().isBefore(OffsetDateTime.now()));
            messages.add(row.getColumnValue("Message").orElseThrow().getValueAsString());
        }
        messages.sort(String::compareTo);
        assertEquals(List.of("boom", "old"), messages);
    }

    @Test
    @Order(4)
    void queryWorkspaceHonoursTheTimeInterval() {
        LogsQueryResult result = queryClient.queryWorkspace(customerId,
                TABLE + " | where Level == 'ERROR' | project Message", LogsQueryTimeInterval.LAST_1_HOUR);

        List<LogsTableRow> rows = result.getTable().getRows();
        assertEquals(1, rows.size());
        assertEquals("boom", rows.getFirst().getColumnValue("Message").orElseThrow().getValueAsString());
    }

    @Test
    @Order(5)
    void queryWorkspaceTakeCapsTheRowCount() {
        LogsQueryResult result = queryClient.queryWorkspace(customerId, TABLE + " | take 1",
                LogsQueryTimeInterval.ALL);

        assertEquals(1, result.getTable().getRows().size());
    }

    @Test
    @Order(6)
    @Disabled("floci-az does not decode Content-Encoding: gzip on the Logs Ingestion API; "
            + "LogsIngestionClient always gzips the upload, so it gets a 400 'Failed to parse request body'")
    void uploadThroughTheLogsIngestionClient() {
        LogsIngestionClient ingestionClient = new LogsIngestionClientBuilder()
                .endpoint(ingestionEndpoint.replace("http://", "https://"))
                .credential(FAKE_CREDENTIAL)
                .addPolicy(new EmulatorConfig.ForceHttpPolicy())
                .buildClient();
        List<Object> logs = List.of(Map.of("TimeGenerated", Instant.now().toString(), "Level", "WARN",
                "Message", "from-sdk"));

        ingestionClient.upload(immutableId, STREAM, logs);

        LogsQueryResult result = queryClient.queryWorkspace(customerId,
                TABLE + " | where Message == 'from-sdk'", LogsQueryTimeInterval.ALL);
        assertEquals(1, result.getTable().getRows().size());
    }

    @Test
    @Order(7)
    void deleteTheMonitorResources() throws Exception {
        assertEquals(200, delete(armUrl("Microsoft.Insights/dataCollectionRules/" + DCR, DCR_API)));
        assertEquals(200, delete(armUrl("Microsoft.Insights/dataCollectionEndpoints/" + DCE, DCR_API)));
        assertEquals(200, delete(armUrl("Microsoft.OperationalInsights/workspaces/" + WORKSPACE, WORKSPACE_API)));
        assertEquals(404, get(armUrl("Microsoft.OperationalInsights/workspaces/" + WORKSPACE, WORKSPACE_API)));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static String httpsBase() {
        return BASE.replace("http://", "https://");
    }

    private static String armUrl(String resource, String apiVersion) {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG + "/providers/" + resource
                + "?api-version=" + apiVersion;
    }

    private static JsonNode putJson(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == 200 || response.statusCode() == 201,
                "PUT " + url + " -> " + response.statusCode() + ": " + response.body());
        return MAPPER.readTree(response.body());
    }

    private static int get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static int delete(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).DELETE().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
