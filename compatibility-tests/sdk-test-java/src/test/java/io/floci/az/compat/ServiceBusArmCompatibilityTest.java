package io.floci.az.compat;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code Microsoft.ServiceBus} management plane end to end: a namespace and a queue created through ARM, with the
 * calls azurerm makes, are usable at once by {@code azure-messaging-servicebus} with the connection string
 * {@code listKeys} answers. ARM is driven with a raw {@link HttpClient}, as the other management-plane suites are.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Service Bus ARM management plane")
class ServiceBusArmCompatibilityTest {

    private static final String BASE = EmulatorConfig.httpBase();
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String RG = "sb-arm-rg-" + SUFFIX;
    private static final String NAMESPACE = "floci-sb-arm-" + SUFFIX;
    private static final String QUEUE = "orders";
    private static final String API = "?api-version=2024-01-01";
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void setup() throws Exception {
        EmulatorConfig.assumeEmulatorRunning();
        EmulatorConfig.ensureServiceBusNamespace();
        Assumptions.assumeFalse(EmulatorConfig.serviceBusMocked,
                "Service Bus is in mocked mode: no broker, so nothing to send to");
        HttpResponse<String> group = put(BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "?api-version=2021-04-01", "{\"location\": \"eastus\"}");
        assertTrue(group.statusCode() == 200 || group.statusCode() == 201, group.body());
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (!EmulatorConfig.serviceBusMocked) {
            delete(namespaceUrl(""));
        }
    }

    @Test
    @Order(1)
    @DisplayName("a namespace is created through ARM as azurerm creates it, and reads Succeeded")
    void createNamespace() throws Exception {
        assertEquals(404, get(namespaceUrl("")).statusCode(), "azurerm checks the name is free first");

        HttpResponse<String> created = put(namespaceUrl(""),
                "{\"location\": \"eastus\", \"sku\": {\"name\": \"Standard\", \"tier\": \"Standard\"},"
                        + " \"properties\": {\"minimumTlsVersion\": \"1.2\", \"publicNetworkAccess\": \"Enabled\","
                        + " \"disableLocalAuth\": false}}");
        assertEquals(201, created.statusCode(), created.body());
        JsonNode body = mapper.readTree(created.body());
        assertEquals("Succeeded", body.path("properties").path("provisioningState").asText());
        assertEquals("Standard", body.path("sku").path("name").asText());

        HttpResponse<String> network = get(namespaceUrl("/networkRuleSets/default"));
        assertEquals(200, network.statusCode(), "azurerm reads the network rule set after every namespace read");
    }

    @Test
    @Order(2)
    @DisplayName("a queue created through ARM takes messages over AMQP with the listKeys connection string")
    void sendAndReceiveWithTheListKeysConnectionString() throws Exception {
        HttpResponse<String> queue = put(namespaceUrl("/queues/" + QUEUE),
                "{\"properties\": {\"maxDeliveryCount\": 5, \"lockDuration\": \"PT30S\"}}");
        assertEquals(200, queue.statusCode(), queue.body());

        HttpResponse<String> keys = post(namespaceUrl("/AuthorizationRules/RootManageSharedAccessKey/listKeys"));
        assertEquals(200, keys.statusCode(), keys.body());
        String connectionString = mapper.readTree(keys.body()).path("primaryConnectionString").asText();
        assertTrue(connectionString.contains(";UseDevelopmentEmulator=true"), connectionString);

        String body = "arm-" + SUFFIX;
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder().connectionString(connectionString)
                .sender().queueName(QUEUE).buildClient()) {
            sender.sendMessage(new ServiceBusMessage(body));
        }
        try (ServiceBusReceiverClient receiver = new ServiceBusClientBuilder().connectionString(connectionString)
                .receiver().queueName(QUEUE).buildClient()) {
            List<ServiceBusReceivedMessage> received = receiver.receiveMessages(1, RECEIVE_TIMEOUT).stream().toList();
            assertEquals(1, received.size(), "the message sent through the ARM connection string comes back");
            assertEquals(body, received.getFirst().getBody().toString());
            receiver.complete(received.getFirst());
        }
    }

    @Test
    @Order(3)
    @DisplayName("an entity rule's connection string names its queue and works on its own")
    void entityRuleConnectionString() throws Exception {
        HttpResponse<String> rule = put(namespaceUrl("/queues/" + QUEUE + "/authorizationRules/sender"),
                "{\"properties\": {\"rights\": [\"Send\", \"Listen\"]}}");
        assertEquals(200, rule.statusCode(), rule.body());
        HttpResponse<String> keys = post(namespaceUrl("/queues/" + QUEUE + "/authorizationRules/sender/ListKeys"));
        String connectionString = mapper.readTree(keys.body()).path("primaryConnectionString").asText();
        assertTrue(connectionString.endsWith(";EntityPath=" + QUEUE), connectionString);

        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder().connectionString(connectionString)
                .sender().buildClient()) {
            sender.sendMessage(new ServiceBusMessage("entity-" + SUFFIX));
        }
        HttpResponse<String> read = get(namespaceUrl("/queues/" + QUEUE));
        assertEquals(1, mapper.readTree(read.body()).path("properties").path("countDetails")
                .path("activeMessageCount").asInt(), read.body());
    }

    @Test
    @Order(4)
    @DisplayName("deleting the namespace removes it")
    void deleteNamespace() throws Exception {
        assertEquals(200, delete(namespaceUrl("")).statusCode());
        assertEquals(404, get(namespaceUrl("")).statusCode());
        assertEquals(204, delete(namespaceUrl("")).statusCode());
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private static String namespaceUrl(String childPath) {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ServiceBus/namespaces/" + NAMESPACE + childPath + API;
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(String url, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
