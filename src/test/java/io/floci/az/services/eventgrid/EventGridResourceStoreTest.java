package io.floci.az.services.eventgrid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.eventgrid.EventGridModels.EventSubscription;
import io.floci.az.services.eventgrid.EventGridModels.Filter;
import io.floci.az.services.eventgrid.EventGridModels.RetryPolicy;
import io.floci.az.services.eventgrid.EventGridModels.Topic;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Event Grid: topics and event subscriptions in stores keyed by canonical id")
class EventGridResourceStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API = "?api-version=2025-02-15";
    private static final String TOPIC_BODY = "{\"location\":\"eastus\",\"properties\":{}}";

    @Inject
    StorageFactory storageFactory;

    private HttpServer webhook;
    private CountDownLatch delivered;

    @BeforeEach
    void setUp() throws Exception {
        given().post("/_admin/reset").then().statusCode(204);
        delivered = new CountDownLatch(1);
        webhook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        webhook.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            delivered.countDown();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        webhook.start();
    }

    @AfterEach
    void tearDown() {
        webhook.stop(0);
    }

    private static String topic(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.EventGrid/topics/" + name;
    }

    private static String topics(String sub, String rg) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.EventGrid/topics" + API;
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneTopic() {
        String id = given().contentType("application/json").body(TOPIC_BODY)
            .when().put(topic("sub-case", "RG-Case", "Topic-One") + API)
            .then().statusCode(200).extract().path("id");
        String firstKey = given().when().post(topic("sub-case", "RG-Case", "Topic-One") + "/listKeys" + API)
            .then().statusCode(200).extract().path("key1");

        given().contentType("application/json").body(TOPIC_BODY)
            .when().put(topic("sub-case", "rg-case", "topic-one") + API)
            .then().statusCode(200)
            .body("id", equalTo(id))
            .body("name", equalTo("Topic-One"));

        given().when().get(topics("sub-case", "rg-case")).then().statusCode(200).body("value", hasSize(1));
        given().when().post(topic("sub-case", "rg-case", "topic-one") + "/listKeys" + API)
            .then().statusCode(200).body("key1", equalTo(firstKey));
    }

    @Test
    void entriesStoredUnderTheOldKeysAreServedDeliveredToAndMoveOnTheirFirstWrite() throws Exception {
        String topicId = topic("Sub-Legacy", "RG-Legacy", "legacy-topic");
        Topic legacyTopic = new Topic("Sub-Legacy", "RG-Legacy", "legacy-topic", "eastus",
            EventGridModels.SCHEMA_EVENT_GRID, "k1", "k2");
        EventSubscription legacySubscription = new EventSubscription("legacy-sub", topicId,
            "http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook", EventGridModels.SCHEMA_EVENT_GRID,
            Filter.empty(), new RetryPolicy(1, 1440), null, null);
        StorageBackend<String, StoredObject> backend = storageFactory.create("eventgrid");
        String oldTopicKey = "eg/topics/Sub-Legacy/RG-Legacy/legacy-topic";
        String oldSubscriptionKey = "eg/subs/" + topicId.toLowerCase() + "/legacy-sub";
        writeOld(backend, oldTopicKey, legacyTopic);
        writeOld(backend, oldSubscriptionKey, legacySubscription);

        given().when().get(topic("sub-legacy", "rg-legacy", "legacy-topic") + API).then().statusCode(200)
            .body("id", equalTo(topicId));
        given().when().get(topics("Sub-Legacy", "RG-Legacy")).then().statusCode(200).body("value", hasSize(1));
        given().when().get(topicId + "/providers/Microsoft.EventGrid/eventSubscriptions" + API)
            .then().statusCode(200).body("value", hasSize(1)).body("value[0].name", equalTo("legacy-sub"));

        String events = "[{\"id\":\"1\",\"subject\":\"/s\",\"eventType\":\"T\",\"eventTime\":\"2026-06-20T00:00:00Z\","
            + "\"dataVersion\":\"1.0\",\"data\":{}}]";
        given().contentType("application/json").body(events)
            .when().post("/legacy-topic-eventgrid/api/events").then().statusCode(200);
        assertTrue(delivered.await(10, TimeUnit.SECONDS), "the legacy subscription received the event");

        given().contentType("application/json").body("{\"keyName\":\"key2\"}")
            .when().post(topicId + "/regenerateKey" + API).then().statusCode(200).body("key1", equalTo("k1"));
        assertFalse(backend.get(oldTopicKey).isPresent(), "the write moved the topic off its old key");
        assertTrue(backend.get(topicId.toLowerCase()).isPresent());

        String subscriptionPath = topicId + "/providers/Microsoft.EventGrid/eventSubscriptions/legacy-sub";
        given().contentType("application/json")
            .body("{\"properties\":{\"destination\":{\"endpointType\":\"WebHook\",\"properties\":{\"endpointUrl\":"
                + "\"http://127.0.0.1:1/hook\"}}}}")
            .when().put(subscriptionPath + API).then().statusCode(200).body("id", equalTo(subscriptionPath));
        assertFalse(backend.get(oldSubscriptionKey).isPresent(), "the write moved the subscription off its old key");
        assertTrue(backend.get(subscriptionPath.toLowerCase()).isPresent());

        given().when().delete(topic("sub-legacy", "rg-legacy", "legacy-topic") + API).then().statusCode(200);
        assertFalse(backend.get(subscriptionPath.toLowerCase()).isPresent(), "deleting the topic took its subscription");
        given().when().get(subscriptionPath + API).then().statusCode(404);
    }

    private static void writeOld(StorageBackend<String, StoredObject> backend, String key, Object value)
            throws Exception {
        backend.put(key, new StoredObject("eg", MAPPER.writeValueAsBytes(value), Map.of(), Instant.now(), "eg"));
    }
}
