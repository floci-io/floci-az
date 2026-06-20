package io.floci.az.compat;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.models.CloudEvent;
import com.azure.core.models.CloudEventDataFormat;
import com.azure.core.util.BinaryData;
import com.azure.messaging.eventgrid.EventGridEvent;
import com.azure.messaging.eventgrid.EventGridPublisherClient;
import com.azure.messaging.eventgrid.EventGridPublisherClientBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real {@code azure-messaging-eventgrid} publisher SDK against floci-az's Event Grid
 * data plane, plus topic provisioning via the ARM control plane.
 *
 * <p>The publish assertions prove wire compatibility (the SDK throws on a non-2xx response). The
 * end-to-end webhook-delivery assertion additionally requires the emulator to reach a webhook
 * hosted by this test process, so it only runs when the emulator endpoint is local.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Event Grid Compatibility")
class EventGridCompatibilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUB = "11111111-1111-1111-1111-111111111111";
    private static final String RG = "compat-rg-eventgrid";
    private static final String API = "?api-version=2025-02-15";

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void setup() throws Exception {
        EmulatorConfig.assumeEmulatorRunning();
        // The Event Grid publisher SDK enforces HTTPS with key credentials. floci-az serves HTTPS
        // on the same port via protocol sniffing; trust its (self-signed) certificate.
        EmulatorConfig.installEmulatorTlsCert();
    }

    /** The publisher SDK requires an HTTPS endpoint; the emulator serves HTTPS on the same port. */
    private static String https(String endpoint) {
        return endpoint.replaceFirst("^http://", "https://");
    }

    /**
     * Builds the topic's data-plane endpoint from the test's own emulator base URL rather than the
     * ARM-returned {@code properties.endpoint}, whose host is the emulator's configured hostname
     * (e.g. {@code floci-az} in Docker Compose) and is not resolvable from the test host.
     */
    private static String dataPlaneEndpoint(String topic) {
        return https(EmulatorConfig.httpBase().replaceAll("/+$", "") + "/" + topic + "-eventgrid/api/events");
    }

    @Test
    @DisplayName("azure-messaging-eventgrid publishes EventGridEvents to a Custom Topic")
    void publishEventGridEvents() throws Exception {
        String topic = "compat-eg-" + shortId();
        String key = createTopicAndKey(topic, "EventGridSchema");

        EventGridPublisherClient<EventGridEvent> client = new EventGridPublisherClientBuilder()
                .endpoint(dataPlaneEndpoint(topic))
                .credential(new AzureKeyCredential(key))
                .buildEventGridEventPublisherClient();

        client.sendEvents(List.of(
                new EventGridEvent("/orders/1", "Order.Created",
                        BinaryData.fromObject(Map.of("orderId", 1)), "1.0"),
                new EventGridEvent("/orders/2", "Order.Updated",
                        BinaryData.fromObject(Map.of("orderId", 2)), "1.0")));
        // No exception means the emulator accepted the SDK's wire format.
    }

    @Test
    @DisplayName("azure-messaging-eventgrid publishes CloudEvents 1.0 to a Custom Topic")
    void publishCloudEvents() throws Exception {
        String topic = "compat-ce-" + shortId();
        String key = createTopicAndKey(topic, "CloudEventSchemaV1_0");

        EventGridPublisherClient<CloudEvent> client = new EventGridPublisherClientBuilder()
                .endpoint(dataPlaneEndpoint(topic))
                .credential(new AzureKeyCredential(key))
                .buildCloudEventPublisherClient();

        client.sendEvent(new CloudEvent("/orders", "Order.Created",
                BinaryData.fromObject(Map.of("orderId", 7)), CloudEventDataFormat.JSON, "application/json"));
    }

    @Test
    @DisplayName("published event is delivered to a subscriber webhook")
    void endToEndWebhookDelivery() throws Exception {
        List<JsonNode> validations = new CopyOnWriteArrayList<>();
        List<JsonNode> notifications = new CopyOnWriteArrayList<>();
        HttpServer webhook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        webhook.createContext("/hook", exchange -> {
            JsonNode root = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            JsonNode first = root.isArray() && root.size() > 0 ? root.get(0) : root;
            String code = first.path("data").path("validationCode").asText(null);
            if (code != null) {
                validations.add(first);
                byte[] resp = ("{\"validationResponse\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
                exchange.getResponseBody().write(resp);
            } else {
                root.forEach(notifications::add);
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        webhook.start();
        try {
            String topic = "compat-e2e-" + shortId();
            createTopic(topic, "EventGridSchema");
            String key = topicKey(topic);
            String webhookUrl = "http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook";
            createSubscription(topic, "sub-e2e", webhookUrl);

            // The emulator must reach this test's webhook to validate it. When the emulator runs in a
            // container (e.g. compat-docker), it cannot reach the host's 127.0.0.1 — skip rather than
            // fail. The in-process EventGridHandlerTest covers delivery deterministically.
            for (int i = 0; i < 20 && validations.isEmpty(); i++) {
                Thread.sleep(100);
            }
            Assumptions.assumeFalse(validations.isEmpty(),
                    "emulator cannot reach this test's webhook (likely containerized); delivery covered in-process");

            EventGridPublisherClient<EventGridEvent> client = new EventGridPublisherClientBuilder()
                    .endpoint(dataPlaneEndpoint(topic))
                    .credential(new AzureKeyCredential(key))
                    .buildEventGridEventPublisherClient();
            client.sendEvent(new EventGridEvent("/orders/42", "Order.Created",
                    BinaryData.fromObject(Map.of("orderId", 42)), "1.0"));

            for (int i = 0; i < 50 && notifications.isEmpty(); i++) {
                Thread.sleep(100);
            }
            assertTrue(!notifications.isEmpty(), "expected the event to be delivered to the webhook");
            assertEquals("Order.Created", notifications.get(0).path("eventType").asText());
        } finally {
            webhook.stop(0);
        }
    }

    // ── ARM provisioning helpers (raw HTTP) ──────────────────────────────────────

    private String createTopic(String topic, String inputSchema) throws Exception {
        String url = EmulatorConfig.httpBase()
                + "/subscriptions/" + SUB + "/resourceGroups/" + RG
                + "/providers/Microsoft.EventGrid/topics/" + topic + API;
        String body = "{\"location\":\"eastus\",\"properties\":{\"inputSchema\":\"" + inputSchema + "\"}}";
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "create topic: " + resp.body());
        String endpoint = MAPPER.readTree(resp.body()).path("properties").path("endpoint").asText(null);
        assertNotNull(endpoint, "topic must expose a data-plane endpoint");
        return endpoint;
    }

    private String createTopicAndKey(String topic, String inputSchema) throws Exception {
        createTopic(topic, inputSchema);
        return topicKey(topic);
    }

    private String topicKey(String topic) throws Exception {
        String url = EmulatorConfig.httpBase()
                + "/subscriptions/" + SUB + "/resourceGroups/" + RG
                + "/providers/Microsoft.EventGrid/topics/" + topic + "/listKeys" + API;
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "listKeys: " + resp.body());
        return MAPPER.readTree(resp.body()).path("key1").asText();
    }

    private void createSubscription(String topic, String name, String webhookUrl) throws Exception {
        String scope = "/subscriptions/" + SUB + "/resourceGroups/" + RG
                + "/providers/Microsoft.EventGrid/topics/" + topic;
        String url = EmulatorConfig.httpBase() + scope
                + "/providers/Microsoft.EventGrid/eventSubscriptions/" + name + API;
        String body = "{\"properties\":{\"destination\":{\"endpointType\":\"WebHook\","
                + "\"properties\":{\"endpointUrl\":\"" + webhookUrl + "\"}}}}";
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "create subscription: " + resp.body());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @AfterAll
    void done() {
    }
}
