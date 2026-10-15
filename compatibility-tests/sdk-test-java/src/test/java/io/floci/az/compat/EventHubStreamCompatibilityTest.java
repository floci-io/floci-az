package io.floci.az.compat;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubConsumerAsyncClient;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.messaging.eventhubs.models.CreateBatchOptions;
import com.azure.messaging.eventhubs.models.EventPosition;
import com.azure.messaging.eventhubs.models.PartitionEvent;
import com.azure.messaging.eventhubs.models.ReceiveOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * In Event Hubs a read never removes an event. Every receiver reads a partition from its own start
 * position, independently of every other receiver, and an event stays until its hub's retention
 * ends. These read the same events several times over, through the Java SDK, and check that an
 * event leaves only when its retention has passed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Event Hubs reads")
class EventHubStreamCompatibilityTest {

    private static final String CONSUMER_GROUP = "$Default";
    /**
     * A consumer group no other test reads, so that only this class's receivers count toward its
     * limits. Each test of the limits reads a partition of its own, so that the receivers one test
     * closes cannot count toward the next one's.
     */
    private static final String LIMITS_CONSUMER_GROUP = "my-consumer-group";
    private static final String PARTITION = "1";
    private static final int EVENT_COUNT = 5;
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(30);
    /** How long a receiver gets to attach before the events it must see are sent. */
    private static final long ATTACH_WAIT_MS = 3_000;

    private EventHubProducerClient producer;
    private String testId;

    @BeforeAll
    void setup() throws Exception {
        EmulatorConfig.assumeEmulatorRunning();
        EmulatorConfig.ensureEventHubNamespace();
        Assumptions.assumeTrue(!EmulatorConfig.eventHubMocked,
                "Event Hubs is in mocked mode (no Artemis broker), SDK tests skipped");
        producer = EmulatorConfig.eventHubClientBuilder().buildProducerClient();
    }

    @AfterAll
    void teardown() {
        if (producer != null) {
            producer.close();
        }
    }

    @BeforeEach
    void newTestId() {
        testId = UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    @DisplayName("two receivers on one partition and consumer group each get every event, sent after they attached")
    void twoReceiversEachGetEveryEvent() throws InterruptedException {
        try (EventHubConsumerAsyncClient first = consumer(); EventHubConsumerAsyncClient second = consumer()) {
            Mono<List<String>> firstReads = read(first, EventPosition.latest());
            Mono<List<String>> secondReads = read(second, EventPosition.latest());
            Mono<List<List<String>>> both = Mono.zip(firstReads, secondReads, List::of).cache();
            both.subscribe();
            Thread.sleep(ATTACH_WAIT_MS);

            send(EVENT_COUNT);

            List<List<String>> reads = both.block(RECEIVE_TIMEOUT);
            assertNotNull(reads, "the receivers did not get the events within " + RECEIVE_TIMEOUT);
            assertEquals(bodies(0, EVENT_COUNT), reads.get(0), "the first receiver");
            assertEquals(bodies(0, EVENT_COUNT), reads.get(1), "the second receiver");
        }
    }

    @Test
    @DisplayName("after a read, a new receiver from the earliest position gets the same events again")
    void aReadRemovesNothing() {
        send(EVENT_COUNT);

        assertEquals(bodies(0, EVENT_COUNT), events(EventPosition.earliest(), event -> true));
        assertEquals(bodies(0, EVENT_COUNT), events(EventPosition.earliest(), event -> true),
                "the second receiver did not get the events the first one read");
    }

    @Test
    @DisplayName("a receiver from a sequence number or an offset re-reads events another receiver read")
    void aPositionReReadsEventsAlreadyRead() {
        send(EVENT_COUNT);
        List<PartitionEvent> read = partitionEvents(EventPosition.earliest());
        assertEquals(EVENT_COUNT, read.size());
        EventData middle = read.get(EVENT_COUNT / 2).getData();

        assertEquals(bodies(EVENT_COUNT / 2, EVENT_COUNT),
                events(EventPosition.fromSequenceNumber(middle.getSequenceNumber(), true), event -> true));
        EventPosition offset = EventPosition.fromOffsetString(middle.getOffsetString());
        assertEquals(bodies(offset.isInclusive() ? EVENT_COUNT / 2 : EVENT_COUNT / 2 + 1, EVENT_COUNT),
                events(offset, event -> true));
    }

    /**
     * A hub of its own, in a namespace of its own, keeps events for three seconds. The event can be
     * read again and again until then, and by no one after.
     */
    @Test
    @DisplayName("an event leaves its partition when its hub's retention ends, and not before")
    void anEventLeavesWhenItsRetentionEnds() throws Exception {
        String namespace = "retain" + testId.substring(0, 8);
        Map<?, ?> created = putNamespace(namespace, "{\"entities\":\"eh1:1:PT3S\",\"consumerGroups\":\"$Default\","
                + "\"amqpPort\":0,\"amqpTlsPort\":0}");
        try {
            String host = EmulatorConfig.EVENTHUB_HOST.endsWith(EmulatorConfig.EVENTHUB_NAMESPACE)
                    ? EmulatorConfig.EVENTHUB_HOST.substring(0,
                            EmulatorConfig.EVENTHUB_HOST.length() - EmulatorConfig.EVENTHUB_NAMESPACE.length()) + namespace
                    : EmulatorConfig.EVENTHUB_HOST;
            JmsConnectionFactory factory = new JmsConnectionFactory("amqp://" + host + ":" + created.get("amqpPort"));
            sendOne(factory, "eh1");

            assertEquals(testId, readOne(factory, "eh1/ConsumerGroups/$Default/Partitions/0"));
            assertEquals(testId, readOne(factory, "eh1/ConsumerGroups/$Default/Partitions/0"),
                    "the event left before its retention ended");
            Thread.sleep(6_000);
            assertNull(readOne(factory, "eh1/ConsumerGroups/$Default/Partitions/0"),
                    "the event stayed after its retention ended");
        } finally {
            deleteNamespace(namespace);
        }
    }

    /**
     * Azure lets five receivers read one partition of one consumer group and refuses the sixth
     * with {@code amqp:resource-limit-exceeded}, which the SDK reports as quota exceeded.
     */
    @Test
    @DisplayName("a partition of a consumer group takes five receivers and refuses the sixth")
    void refusesTheSixthReceiver() throws InterruptedException {
        List<EventHubConsumerAsyncClient> five = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                EventHubConsumerAsyncClient receiver = limitsConsumer();
                five.add(receiver);
                receiver.receiveFromPartition(PARTITION, EventPosition.latest()).subscribe();
            }
            Thread.sleep(ATTACH_WAIT_MS);

            try (EventHubConsumerAsyncClient sixth = limitsConsumer()) {
                Throwable refused = sixth.receiveFromPartition(PARTITION, EventPosition.latest())
                        .then().materialize().block(RECEIVE_TIMEOUT).getThrowable();
                assertEquals(AmqpErrorCondition.RESOURCE_LIMIT_EXCEEDED, condition(refused), String.valueOf(refused));
            }
        } finally {
            five.forEach(EventHubConsumerAsyncClient::close);
        }
    }

    /**
     * A receiver with an owner level, which Azure calls the epoch, owns its partition in its
     * consumer group. One with a higher owner level disconnects it with {@code amqp:link:stolen},
     * which the SDK reports as the consumer disconnected.
     */
    @Test
    @DisplayName("a receiver with a higher owner level disconnects the current owner")
    void aHigherOwnerLevelDisconnectsTheOwner() throws InterruptedException {
        try (EventHubConsumerAsyncClient owner = limitsConsumer(); EventHubConsumerAsyncClient next = limitsConsumer()) {
            Mono<Signal<Void>> ownerEnds = owner.receiveFromPartition("2", EventPosition.latest(), ownerLevel(1))
                    .then().materialize().cache();
            ownerEnds.subscribe();
            Thread.sleep(ATTACH_WAIT_MS);

            next.receiveFromPartition("2", EventPosition.latest(), ownerLevel(2)).subscribe();

            Throwable disconnected = ownerEnds.block(RECEIVE_TIMEOUT).getThrowable();
            assertEquals(AmqpErrorCondition.LINK_STOLEN, condition(disconnected), String.valueOf(disconnected));
        }
    }

    @Test
    @DisplayName("a receiver without an owner level is refused while the partition has an owner")
    void aReceiverWithoutOwnerLevelIsRefusedWhileAnOwnerHolds() throws InterruptedException {
        try (EventHubConsumerAsyncClient owner = limitsConsumer(); EventHubConsumerAsyncClient plain = limitsConsumer()) {
            owner.receiveFromPartition("3", EventPosition.latest(), ownerLevel(1)).subscribe();
            Thread.sleep(ATTACH_WAIT_MS);

            Throwable refused = plain.receiveFromPartition("3", EventPosition.latest())
                    .then().materialize().block(RECEIVE_TIMEOUT).getThrowable();
            assertEquals(AmqpErrorCondition.LINK_STOLEN, condition(refused), String.valueOf(refused));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ReceiveOptions ownerLevel(long level) {
        return new ReceiveOptions().setOwnerLevel(level);
    }

    /** A consumer on {@link #LIMITS_CONSUMER_GROUP} that does not retry, so a refusal surfaces at once. */
    private EventHubConsumerAsyncClient limitsConsumer() {
        return EmulatorConfig.eventHubClientBuilder()
                .consumerGroup(LIMITS_CONSUMER_GROUP)
                .retryOptions(new AmqpRetryOptions().setMaxRetries(0))
                .buildAsyncConsumerClient();
    }

    /** The AMQP error condition behind an error the SDK raised, or null if it carries none. */
    private static AmqpErrorCondition condition(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AmqpException amqp && amqp.getErrorCondition() != null) {
                return amqp.getErrorCondition();
            }
        }
        return null;
    }

    private EventHubConsumerAsyncClient consumer() {
        return EmulatorConfig.eventHubClientBuilder().consumerGroup(CONSUMER_GROUP).buildAsyncConsumerClient();
    }

    /** The bodies of this test's first {@link #EVENT_COUNT} events from {@code position}, in order. */
    private Mono<List<String>> read(EventHubConsumerAsyncClient consumer, EventPosition position) {
        return consumer.receiveFromPartition(PARTITION, position)
                .filter(event -> testId.equals(event.getData().getProperties().get("testId")))
                .take(EVENT_COUNT)
                .map(event -> event.getData().getBodyAsString())
                .collectList();
    }

    /** The bodies of this test's events a new receiver reads from {@code position}. */
    private List<String> events(EventPosition position, Predicate<EventData> keep) {
        return partitionEvents(position).stream().map(PartitionEvent::getData).filter(keep)
                .map(EventData::getBodyAsString).toList();
    }

    /** This test's events a new receiver reads from {@code position}, until no more arrive. */
    private List<PartitionEvent> partitionEvents(EventPosition position) {
        try (EventHubConsumerAsyncClient consumer = consumer()) {
            List<PartitionEvent> events = consumer.receiveFromPartition(PARTITION, position)
                    .filter(event -> testId.equals(event.getData().getProperties().get("testId")))
                    .take(Duration.ofSeconds(5))
                    .collectList()
                    .block(RECEIVE_TIMEOUT);
            return events == null ? List.of() : events;
        }
    }

    private void send(int count) {
        EventDataBatch batch = producer.createBatch(new CreateBatchOptions().setPartitionId(PARTITION));
        for (int i = 0; i < count; i++) {
            EventData event = new EventData("event-" + i);
            event.getProperties().put("testId", testId);
            batch.tryAdd(event);
        }
        producer.send(batch);
    }

    private static List<String> bodies(int from, int to) {
        List<String> bodies = new ArrayList<>();
        for (int i = from; i < to; i++) {
            bodies.add("event-" + i);
        }
        return bodies;
    }

    private void sendOne(JmsConnectionFactory factory, String address) throws JMSException {
        try (Connection connection = factory.createConnection();
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            MessageProducer sender = session.createProducer(session.createQueue(address));
            TextMessage event = session.createTextMessage(testId);
            event.setStringProperty("testId", testId);
            sender.send(event);
        }
    }

    /** The body of this test's event on the queue, or null if no receiver gets one. */
    private String readOne(JmsConnectionFactory factory, String queue) throws JMSException {
        try (Connection connection = factory.createConnection();
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            connection.start();
            MessageConsumer receiver = session.createConsumer(session.createQueue(queue), "testId = '" + testId + "'");
            TextMessage event = (TextMessage) receiver.receive(3_000);
            return event == null ? null : event.getText();
        }
    }

    private static Map<?, ?> putNamespace(String namespace, String body) throws Exception {
        HttpURLConnection request = (HttpURLConnection) URI.create(namespaceUrl(namespace)).toURL().openConnection();
        request.setRequestMethod("PUT");
        request.setDoOutput(true);
        request.setReadTimeout(120_000);
        request.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = request.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        assertFalse(request.getResponseCode() >= 400, "creating namespace " + namespace + ": HTTP " + request.getResponseCode());
        return new ObjectMapper().readValue(request.getInputStream(), Map.class);
    }

    private static void deleteNamespace(String namespace) throws Exception {
        HttpURLConnection request = (HttpURLConnection) URI.create(namespaceUrl(namespace)).toURL().openConnection();
        request.setRequestMethod("DELETE");
        request.getResponseCode();
    }

    private static String namespaceUrl(String namespace) {
        return EmulatorConfig.httpBase() + "/" + EmulatorConfig.ACCOUNT + "-eventhub/namespaces/" + namespace;
    }
}
