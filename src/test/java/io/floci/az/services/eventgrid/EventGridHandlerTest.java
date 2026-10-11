package io.floci.az.services.eventgrid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quarkus-level tests for {@link EventGridHandler}.
 *
 * <p>Covers the ARM control plane (topic CRUD + listKeys), the subscription validation handshake,
 * and end-to-end publish → webhook delivery with subject filtering. A small in-process
 * {@link HttpServer} stands in for the subscriber webhook so deliveries can be asserted without a
 * new dependency.
 */
@QuarkusTest
@DisplayName("EventGridHandler — ARM, publish, and webhook delivery")
class EventGridHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SUB = "test-sub-eventgrid";
    private static final String RG = "test-rg-eventgrid";
    private static final String TOPIC = "orders-topic";
    private static final String API = "?api-version=2025-02-15";
    private static final String TOPIC_SCOPE =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.EventGrid/topics/" + TOPIC;
    private static final String TOPIC_BASE =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.EventGrid/topics/" + TOPIC;

    private static final String ACCOUNT = "devstoreaccount1";
    private static final String STORAGE_ACCOUNT_ID =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.Storage/storageAccounts/" + ACCOUNT;

    private HttpServer webhook;
    private final List<JsonNode> validations = new CopyOnWriteArrayList<>();
    private final List<JsonNode> notifications = new CopyOnWriteArrayList<>();
    private final AtomicInteger failedDeliveries = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        given().post("/_admin/reset").then().statusCode(204);
        validations.clear();
        notifications.clear();
        failedDeliveries.set(0);

        webhook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        webhook.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            JsonNode root = MAPPER.readTree(body);
            JsonNode first = root.isArray() && root.size() > 0 ? root.get(0) : root;
            String validationCode = first.path("data").path("validationCode").asText(null);
            if (validationCode != null) {
                validations.add(first);
                byte[] resp = ("{\"validationResponse\":\"" + validationCode + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                exchange.getResponseBody().write(resp);
            } else {
                root.forEach(notifications::add);
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        webhook.createContext("/fail", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if ("Notification".equals(exchange.getRequestHeaders().getFirst("aeg-event-type"))) {
                failedDeliveries.incrementAndGet();
            }
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        webhook.start();
    }

    @AfterEach
    void tearDown() {
        if (webhook != null) {
            webhook.stop(0);
        }
    }

    private String webhookUrl() {
        return "http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook";
    }

    private void createTopic() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{}}")
            .when().put(TOPIC_BASE + API)
            .then().statusCode(200)
            .body("name", equalTo(TOPIC))
            .body("type", equalTo("Microsoft.EventGrid/topics"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.endpoint", notNullValue());
    }

    private void createSubscription(String subscriptionName, String subjectBeginsWith) {
        String filter = subjectBeginsWith == null ? "{}"
                : "{\"subjectBeginsWith\":\"" + subjectBeginsWith + "\"}";
        String body = "{\"properties\":{"
                + "\"destination\":{\"endpointType\":\"WebHook\",\"properties\":{\"endpointUrl\":\""
                + webhookUrl() + "\"}},"
                + "\"filter\":" + filter + "}}";
        given()
            .contentType("application/json")
            .body(body)
            .when().put(TOPIC_SCOPE + "/providers/Microsoft.EventGrid/eventSubscriptions/" + subscriptionName + API)
            .then().statusCode(200)
            .body("name", equalTo(subscriptionName))
            .body("type", equalTo("Microsoft.EventGrid/eventSubscriptions"))
            .body("properties.destination.properties.endpointUrl", equalTo(webhookUrl()));
    }

    @Test
    @DisplayName("topic listKeys returns key1 and key2")
    void listKeysReturnsBothKeys() {
        createTopic();
        given()
            .when().post(TOPIC_BASE + "/listKeys" + API)
            .then().statusCode(200)
            .body("key1", notNullValue())
            .body("key2", notNullValue());
    }

    @Test
    @DisplayName("creating a webhook subscription runs the validation handshake")
    void subscriptionCreationValidatesWebhook() {
        createTopic();
        createSubscription("sub-validate", null);

        assertEquals(1, validations.size(), "subscriber should receive exactly one validation event");
        assertEquals("Microsoft.EventGrid.SubscriptionValidationEvent",
                validations.get(0).path("eventType").asText());
    }

    @Test
    @DisplayName("published event is delivered to a matching subscription with topic set to the resource id")
    void publishDeliversToMatchingSubscription() throws InterruptedException {
        createTopic();
        createSubscription("sub-all", "/orders");

        String events = "[{"
                + "\"id\":\"evt-1\","
                + "\"subject\":\"/orders/123\","
                + "\"eventType\":\"Order.Created\","
                + "\"eventTime\":\"2026-06-20T00:00:00Z\","
                + "\"dataVersion\":\"1.0\","
                + "\"data\":{\"orderId\":123}"
                + "}]";
        given()
            .contentType("application/json").body(events)
            .when().post("/" + TOPIC + "-eventgrid/api/events")
            .then().statusCode(200);

        JsonNode delivered = awaitNotification();
        assertEquals("Order.Created", delivered.path("eventType").asText());
        assertEquals("/orders/123", delivered.path("subject").asText());
        assertEquals(TOPIC_SCOPE, delivered.path("topic").asText(),
                "delivered event topic must be the topic's full resource id");
        assertEquals("1", delivered.path("metadataVersion").asText());
    }

    @Test
    @DisplayName("subjectBeginsWith filter excludes non-matching events")
    void filterExcludesNonMatchingEvents() throws InterruptedException {
        createTopic();
        createSubscription("sub-filtered", "/orders");

        String events = "[{"
                + "\"id\":\"evt-2\",\"subject\":\"/shipments/9\",\"eventType\":\"Shipment.Created\","
                + "\"eventTime\":\"2026-06-20T00:00:00Z\",\"dataVersion\":\"1.0\",\"data\":{}}]";
        given()
            .contentType("application/json").body(events)
            .when().post("/" + TOPIC + "-eventgrid/api/events")
            .then().statusCode(200);

        // Give the async delivery scheduler a chance to (not) deliver.
        Thread.sleep(1000);
        assertTrue(notifications.isEmpty(), "event under /shipments should not match subjectBeginsWith=/orders");
    }

    @Test
    @DisplayName("a StorageBlob dead-letter destination round-trips on GET")
    void deadLetterDestinationRoundTrips() {
        createTopic();
        String body = "{\"properties\":{"
                + "\"destination\":{\"endpointType\":\"WebHook\",\"properties\":{\"endpointUrl\":\""
                + webhookUrl() + "\"}},"
                + "\"deadLetterDestination\":" + deadLetterJson("dl-roundtrip") + ","
                + "\"deadLetterWithResourceIdentity\":{\"identity\":{\"type\":\"SystemAssigned\"},"
                + "\"deadLetterDestination\":" + deadLetterJson("dl-identity") + "}}}";
        String url = TOPIC_SCOPE + "/providers/Microsoft.EventGrid/eventSubscriptions/sub-dl-echo" + API;
        given().contentType("application/json").body(body)
            .when().put(url)
            .then().statusCode(200);

        given().when().get(url)
            .then().statusCode(200)
            .body("properties.deadLetterDestination.endpointType", equalTo("StorageBlob"))
            .body("properties.deadLetterDestination.properties.resourceId", equalTo(STORAGE_ACCOUNT_ID))
            .body("properties.deadLetterDestination.properties.blobContainerName", equalTo("dl-roundtrip"))
            .body("properties.deadLetterWithResourceIdentity.identity.type", equalTo("SystemAssigned"))
            .body("properties.deadLetterWithResourceIdentity.deadLetterDestination.endpointType",
                    equalTo("StorageBlob"))
            .body("properties.deadLetterWithResourceIdentity.deadLetterDestination.properties.blobContainerName",
                    equalTo("dl-identity"));
    }

    @Test
    @DisplayName("an event that exhausts its attempts is written to the dead-letter container")
    void exhaustedEventIsDeadLetteredToBlob() throws Exception {
        createTopic();
        createContainer("deadletter-eg");
        createFailingSubscription("Orders-DL", "\"deadLetterDestination\":" + deadLetterJson("deadletter-eg") + ",");

        publishOrderEvent("evt-dl-1");

        List<String> blobs = awaitBlobs("deadletter-eg");
        assertEquals(1, blobs.size(), "exactly one dead-letter blob expected");
        String name = blobs.get(0);
        assertTrue(name.matches("ORDERS-DL/\\d{4}/\\d{1,2}/\\d{1,2}/\\d{1,2}/[0-9a-f-]{36}\\.json"),
                "unexpected dead-letter blob name: " + name);

        byte[] content = given().when().get("/" + ACCOUNT + "/deadletter-eg/" + name)
                .then().statusCode(200)
                .extract().asByteArray();
        JsonNode root = MAPPER.readTree(content);
        assertTrue(root.isArray(), "dead-letter blob holds a JSON array");
        assertEquals(1, root.size());
        JsonNode event = root.get(0);
        assertEquals("evt-dl-1", event.path("id").asText());
        assertEquals("Order.Created", event.path("eventType").asText());
        assertEquals(TOPIC_SCOPE, event.path("topic").asText());
        assertEquals(123, event.path("data").path("orderId").asInt());
        assertEquals("MaxDeliveryAttemptsExceeded", event.path("deadLetterReason").asText());
        assertEquals(2, event.path("deliveryAttempts").asInt());
        assertEquals("Busy", event.path("lastDeliveryOutcome").asText());
        assertEquals(500, event.path("lastHttpStatusCode").asInt());
        assertTrue(event.hasNonNull("publishTime"));
        assertTrue(event.hasNonNull("lastDeliveryAttemptTime"));
    }

    @Test
    @DisplayName("without a dead-letter destination an exhausted event is dropped and nothing is written")
    void exhaustedEventWithoutDeadLetterIsDropped() throws InterruptedException {
        createTopic();
        createContainer("deadletter-none");
        createFailingSubscription("orders-nodl", "");

        publishOrderEvent("evt-dl-2");

        for (int i = 0; i < 50 && failedDeliveries.get() < 2; i++) {
            Thread.sleep(100);
        }
        assertEquals(2, failedDeliveries.get(), "both attempts should reach the failing endpoint");
        Thread.sleep(500);
        assertTrue(listBlobs("deadletter-none").isEmpty(), "no blob may be written without a dead-letter destination");
    }

    private static String deadLetterJson(String container) {
        return "{\"endpointType\":\"StorageBlob\",\"properties\":{\"resourceId\":\"" + STORAGE_ACCOUNT_ID
                + "\",\"blobContainerName\":\"" + container + "\"}}";
    }

    private void createContainer(String container) {
        given().when().put("/" + ACCOUNT + "/" + container + "?restype=container")
            .then().statusCode(201);
    }

    private void createFailingSubscription(String name, String extraProperties) {
        String failUrl = "http://127.0.0.1:" + webhook.getAddress().getPort() + "/fail";
        String body = "{\"properties\":{" + extraProperties
                + "\"destination\":{\"endpointType\":\"WebHook\",\"properties\":{\"endpointUrl\":\""
                + failUrl + "\"}},"
                + "\"retryPolicy\":{\"maxDeliveryAttempts\":2,\"eventTimeToLiveInMinutes\":1440}}}";
        given().contentType("application/json").body(body)
            .when().put(TOPIC_SCOPE + "/providers/Microsoft.EventGrid/eventSubscriptions/" + name + API)
            .then().statusCode(200);
    }

    private void publishOrderEvent(String id) {
        String events = "[{\"id\":\"" + id + "\",\"subject\":\"/orders/123\",\"eventType\":\"Order.Created\","
                + "\"eventTime\":\"2026-06-20T00:00:00Z\",\"dataVersion\":\"1.0\",\"data\":{\"orderId\":123}}]";
        given().contentType("application/json").body(events)
            .when().post("/" + TOPIC + "-eventgrid/api/events")
            .then().statusCode(200);
    }

    private List<String> listBlobs(String container) {
        return given().when().get("/" + ACCOUNT + "/" + container + "?restype=container&comp=list")
                .then().statusCode(200)
                .extract().xmlPath().getList("EnumerationResults.Blobs.Blob.Name", String.class);
    }

    private List<String> awaitBlobs(String container) throws InterruptedException {
        List<String> blobs = listBlobs(container);
        for (int i = 0; i < 50 && blobs.isEmpty(); i++) {
            Thread.sleep(100);
            blobs = listBlobs(container);
        }
        assertTrue(!blobs.isEmpty(), "expected a dead-letter blob within the timeout");
        return blobs;
    }

    private JsonNode awaitNotification() throws InterruptedException {
        for (int i = 0; i < 50 && notifications.isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertTrue(!notifications.isEmpty(), "expected a delivered notification within the timeout");
        return notifications.get(0);
    }
}
