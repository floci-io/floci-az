package io.floci.az.compat;

import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubConsumerClient;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.messaging.eventhubs.EventHubProperties;
import com.azure.messaging.eventhubs.models.CreateBatchOptions;
import com.azure.messaging.eventhubs.models.EventPosition;
import com.azure.messaging.eventhubs.models.PartitionEvent;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Event Hubs compatibility tests driven by the Azure Event Hubs SDK.
 *
 * The sibling {@link EventHubCompatibilityTest} speaks raw AMQP through JMS, which reaches the
 * broker without ever asking it for anything Event Hubs specific. This one goes through the SDK a
 * real application uses, so it covers what only the SDK asks for: the hub's partition list over
 * {@code $management}, a send fanned out across those partitions, and a receiver that names where
 * in the stream to start.
 *
 * The start position is the part worth stating plainly: the SDK expresses every one of them —
 * "earliest" included — as an AMQP selector over a message annotation, so a receiver that cannot
 * have its start position honoured cannot attach at all.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Event Hubs SDK Compatibility")
class EventHubSdkCompatibilityTest {

    private static final String CONSUMER_GROUP = "$Default";
    /**
     * A second consumer group, which holds its own copy of every partition. Reading an event
     * removes it from the queue it was read from, so the tests that start a receiver at a
     * position learn the positions here and leave the events in {@link #CONSUMER_GROUP} unread.
     */
    private static final String POSITIONS_CONSUMER_GROUP = "my-consumer-group";
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(3);
    private static final int EVENT_COUNT = 8;
    /**
     * How many events one read takes off a partition. Comfortably more than this test sends, so an
     * earlier test's leftovers cannot fill the read and starve out this test's own events.
     */
    private static final int RECEIVE_LIMIT = EVENT_COUNT * 4;
    /**
     * How many events a read of a whole partition takes at most. The tests from 07 on read their
     * own events back from one partition, which by then holds the events of the earlier tests too.
     */
    private static final int PARTITION_READ_LIMIT = 1_000;
    /** The pause between two sends whose events must not share an enqueued time. */
    private static final long SEND_PAUSE_MS = 5;
    /** The annotations Azure sets on every event it delivers, which the SDK reads the position from. */
    private static final Set<String> POSITION_ANNOTATIONS =
            Set.of("x-opt-sequence-number", "x-opt-offset", "x-opt-enqueued-time");

    private EventHubProducerClient producer;
    private String testId;

    @BeforeAll
    void setup() throws Exception {
        EmulatorConfig.assumeEmulatorRunning();
        EmulatorConfig.ensureEventHubNamespace();
        Assumptions.assumeTrue(!EmulatorConfig.eventHubMocked,
                "Event Hubs is in mocked mode (no Artemis broker) — SDK tests skipped");
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
    @DisplayName("01. Hub properties list the configured partitions")
    void hubPropertiesListPartitions() {
        EventHubProperties properties = producer.getEventHubProperties();

        assertEquals(EmulatorConfig.EVENTHUB_NAME, properties.getName());
        List<String> partitionIds = new ArrayList<>();
        properties.getPartitionIds().forEach(partitionIds::add);
        assertFalse(partitionIds.isEmpty(), "the hub reported no partitions");
        assertEquals(partitionIds.size(), new HashSet<>(partitionIds).size(),
                "partition ids are not distinct: " + partitionIds);
    }

    @Test
    @DisplayName("02. Events sent without a key spread over the partitions")
    void eventsSpreadOverPartitions() {
        List<String> partitionIds = partitionIds();
        Assumptions.assumeTrue(partitionIds.size() > 1,
                "the hub has one partition — nothing to spread over");

        send(EVENT_COUNT);

        Set<String> partitionsUsed = new HashSet<>();
        int received = 0;
        for (String partitionId : partitionIds) {
            List<String> bodies = receiveFrom(partitionId, EventPosition.earliest());
            received += bodies.size();
            if (!bodies.isEmpty()) {
                partitionsUsed.add(partitionId);
            }
        }

        assertEquals(EVENT_COUNT, received, "not every event was received back");
        assertTrue(partitionsUsed.size() > 1,
                "every event landed in one partition: " + partitionsUsed);
    }

    @Test
    @DisplayName("03. A partition key keeps its events together")
    void partitionKeyKeepsEventsTogether() {
        String key = "key-" + testId;
        try (EventHubProducerClient keyed = EmulatorConfig.eventHubClientBuilder().buildProducerClient()) {
            var batch = keyed.createBatch(new CreateBatchOptions().setPartitionKey(key));
            for (int i = 0; i < EVENT_COUNT; i++) {
                assertTrue(batch.tryAdd(event(i)), "the batch would not take event " + i);
            }
            keyed.send(batch);
        }

        Set<String> partitionsUsed = new HashSet<>();
        int received = 0;
        for (String partitionId : partitionIds()) {
            List<String> bodies = receiveFrom(partitionId, EventPosition.earliest());
            received += bodies.size();
            if (!bodies.isEmpty()) {
                partitionsUsed.add(partitionId);
            }
        }

        assertEquals(EVENT_COUNT, received, "not every event was received back");
        assertEquals(1, partitionsUsed.size(),
                "one partition key was spread over several partitions: " + partitionsUsed);
    }

    /**
     * The SDK addresses a pinned partition as {@code {hub}/Partitions/{id}} rather than annotating
     * the message, so this covers a send that never reaches the partition-choosing code at all.
     * Without a sender address at that path the send is acked against an auto-created address and
     * the events are gone.
     */
    @Test
    @DisplayName("04. Events sent to a named partition arrive on that partition")
    void explicitPartitionSendsArriveOnThatPartition() {
        List<String> partitionIds = partitionIds();
        String target = partitionIds.get(partitionIds.size() - 1);

        try (EventHubProducerClient pinned = EmulatorConfig.eventHubClientBuilder().buildProducerClient()) {
            var batch = pinned.createBatch(new CreateBatchOptions().setPartitionId(target));
            for (int i = 0; i < EVENT_COUNT; i++) {
                assertTrue(batch.tryAdd(event(i)), "the batch would not take event " + i);
            }
            pinned.send(batch);
        }

        for (String partitionId : partitionIds) {
            List<String> bodies = receiveFrom(partitionId, EventPosition.earliest());
            if (partitionId.equals(target)) {
                assertEquals(EVENT_COUNT, bodies.size(),
                        "partition " + target + " did not receive the events addressed to it");
            } else {
                assertEquals(List.of(), bodies,
                        "partition " + partitionId + " received events addressed to " + target);
            }
        }
    }

    @Test
    @DisplayName("05. A receiver started at the latest position skips what came before")
    void latestPositionSkipsEarlierEvents() {
        List<String> partitionIds = partitionIds();
        send(EVENT_COUNT);

        int received = 0;
        for (String partitionId : partitionIds) {
            received += receiveFrom(partitionId, EventPosition.latest()).size();
        }

        assertEquals(0, received, "a receiver at the latest position replayed earlier events");
    }

    /**
     * A producer and a consumer that spell the hub's address differently still meet.
     *
     * The SDK names the entity alone; the Python and Rust SDKs name it under
     * {@code {scheme}://{host}/}. Both are the same hub, so both have to reach the same partition
     * queues. They did not while the topology was generated once per spelling: each spelling had
     * its own private set of queues, and every one-SDK test passed because it used the same
     * spelling at both ends. Qpid JMS stands in for the other SDKs here — what matters is the
     * address on the wire, not which client library wrote it.
     */
    @Test
    @DisplayName("06. A producer addressing the hub by URI reaches the same partitions")
    void uriAddressedSendsReachTheSamePartitions() throws JMSException {
        String uriForm = "amqps://" + EmulatorConfig.eventHubFullyQualifiedNamespace()
                + "/" + EmulatorConfig.EVENTHUB_NAME;

        try (Connection conn = EmulatorConfig.buildAmqpConnectionFactory().createConnection();
             Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            conn.start();
            MessageProducer producer = session.createProducer(session.createQueue(uriForm));
            for (int i = 0; i < EVENT_COUNT; i++) {
                TextMessage msg = session.createTextMessage("event-" + i);
                msg.setStringProperty("testId", testId);
                producer.send(msg);
            }
        }

        int received = 0;
        for (String partitionId : partitionIds()) {
            received += receiveFrom(partitionId, EventPosition.earliest()).size();
        }
        assertEquals(EVENT_COUNT, received,
                "events sent to " + uriForm + " did not reach the partitions the SDK reads");
    }

    /**
     * The broker keeps each event's partition and stream position for its own diverts and
     * selectors. Azure sends neither as an application property, only the position, as
     * annotations, so that is all a client may receive.
     */
    @Test
    @DisplayName("07. An event carries its position as Azure sends it, and no floci property")
    void eventsCarryTheirPositionAsAzureSendsIt() {
        String partitionId = partitionIds().get(0);
        Instant sentFrom = Instant.now();
        sendTo(partitionId, 0, EVENT_COUNT);

        List<EventData> events = receiveEvents(CONSUMER_GROUP, partitionId, EventPosition.earliest());

        assertEquals(EVENT_COUNT, events.size(), "not every event was received back");
        long previous = -1;
        for (EventData event : events) {
            List<String> internal = event.getProperties().keySet().stream()
                    .filter(key -> key.startsWith("floci_")).toList();
            assertEquals(List.of(), internal, "the event carries properties Azure never sends");
            Map<String, Object> annotations = event.getRawAmqpMessage().getMessageAnnotations();
            assertTrue(annotations.keySet().containsAll(POSITION_ANNOTATIONS),
                    "the event lacks a position annotation: " + annotations.keySet());
            long sequence = event.getSequenceNumber();
            assertTrue(sequence > previous, "sequence numbers do not increase: " + previous + ", " + sequence);
            assertEquals(Long.toString(sequence), event.getOffsetString());
            assertFalse(event.getEnqueuedTime().isBefore(sentFrom.minusSeconds(1)),
                    "enqueued before it was sent: " + event.getEnqueuedTime());
            assertFalse(event.getEnqueuedTime().isAfter(Instant.now()),
                    "enqueued in the future: " + event.getEnqueuedTime());
            previous = sequence;
        }
    }

    @Test
    @DisplayName("08. A receiver started at a sequence number reads from that event on")
    void sequenceNumberPositionReadsFromThere() {
        String partitionId = partitionIds().get(0);
        sendTo(partitionId, 0, EVENT_COUNT);
        List<EventData> events = receiveEvents(POSITIONS_CONSUMER_GROUP, partitionId, EventPosition.earliest());
        long middle = events.get(EVENT_COUNT / 2).getSequenceNumber();

        assertReadsFrom(partitionId, EventPosition.fromSequenceNumber(middle, true), events,
                event -> event.getSequenceNumber() >= middle);
    }

    @Test
    @DisplayName("09. A receiver started at an offset reads the events after it")
    void offsetPositionReadsFromThere() {
        String partitionId = partitionIds().get(0);
        sendTo(partitionId, 0, EVENT_COUNT);
        List<EventData> events = receiveEvents(POSITIONS_CONSUMER_GROUP, partitionId, EventPosition.earliest());
        long middle = Long.parseLong(events.get(EVENT_COUNT / 2).getOffsetString());

        EventPosition position = EventPosition.fromOffsetString(Long.toString(middle));

        assertReadsFrom(partitionId, position, events, event -> position.isInclusive()
                ? Long.parseLong(event.getOffsetString()) >= middle
                : Long.parseLong(event.getOffsetString()) > middle);
    }

    @Test
    @DisplayName("10. A receiver started at an enqueued time reads the events after it")
    void enqueuedTimePositionReadsFromThere() throws InterruptedException {
        String partitionId = partitionIds().get(0);
        // One event per send, some milliseconds apart: the enqueued time has millisecond
        // resolution, and events that share one cannot tell whether the position is honoured.
        for (int i = 0; i < EVENT_COUNT; i++) {
            sendTo(partitionId, i, 1);
            Thread.sleep(SEND_PAUSE_MS);
        }
        List<EventData> events = receiveEvents(POSITIONS_CONSUMER_GROUP, partitionId, EventPosition.earliest());
        Instant middle = events.get(EVENT_COUNT / 2).getEnqueuedTime();

        EventPosition position = EventPosition.fromEnqueuedTime(middle);

        assertReadsFrom(partitionId, position, events, event -> position.isInclusive()
                ? !event.getEnqueuedTime().isBefore(middle)
                : event.getEnqueuedTime().isAfter(middle));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<String> partitionIds() {
        List<String> partitionIds = new ArrayList<>();
        producer.getPartitionIds().forEach(partitionIds::add);
        return partitionIds;
    }

    private EventData event(int index) {
        EventData event = new EventData("event-" + index);
        event.getProperties().put("testId", testId);
        return event;
    }

    private void send(int count) {
        for (int i = 0; i < count; i++) {
            var batch = producer.createBatch();
            assertTrue(batch.tryAdd(event(i)), "the batch would not take event " + i);
            producer.send(batch);
        }
    }

    /** Sends events {@code first} to {@code first + count - 1} to one partition, in one batch. */
    private void sendTo(String partitionId, int first, int count) {
        EventDataBatch batch = producer.createBatch(new CreateBatchOptions().setPartitionId(partitionId));
        for (int i = first; i < first + count; i++) {
            assertTrue(batch.tryAdd(event(i)), "the batch would not take event " + i);
        }
        producer.send(batch);
    }

    /**
     * A receiver at {@code position} reads exactly the events {@code fromPosition} keeps, and those
     * are some of the events but not all, so the read shows that the earlier ones are skipped.
     */
    private void assertReadsFrom(String partitionId, EventPosition position, List<EventData> events,
                                 Predicate<EventData> fromPosition) {
        List<String> expected = bodies(events, fromPosition);
        assertFalse(expected.isEmpty(), position + " selects none of the events");
        assertTrue(expected.size() < events.size(), position + " selects every event");
        assertEquals(expected, bodies(receiveEvents(CONSUMER_GROUP, partitionId, position), event -> true));
    }

    /**
     * This test's own events on one partition of {@code consumerGroup}, from {@code position}
     * onwards, in order.
     */
    private List<EventData> receiveEvents(String consumerGroup, String partitionId, EventPosition position) {
        List<EventData> events = new ArrayList<>();
        try (EventHubConsumerClient consumer =
                     EmulatorConfig.eventHubClientBuilder()
                             .consumerGroup(consumerGroup)
                             .buildConsumerClient()) {
            for (PartitionEvent event :
                    consumer.receiveFromPartition(partitionId, PARTITION_READ_LIMIT, position, RECEIVE_TIMEOUT)) {
                if (testId.equals(event.getData().getProperties().get("testId"))) {
                    events.add(event.getData());
                }
            }
        }
        return events;
    }

    private static List<String> bodies(List<EventData> events, Predicate<EventData> keep) {
        return events.stream().filter(keep).map(EventData::getBodyAsString).toList();
    }

    /** Bodies of this test's own events on one partition, from {@code position} onwards. */
    private List<String> receiveFrom(String partitionId, EventPosition position) {
        List<String> bodies = new ArrayList<>();
        try (EventHubConsumerClient consumer =
                     EmulatorConfig.eventHubClientBuilder()
                             .consumerGroup(CONSUMER_GROUP)
                             .buildConsumerClient()) {
            for (PartitionEvent event :
                    consumer.receiveFromPartition(partitionId, RECEIVE_LIMIT, position, RECEIVE_TIMEOUT)) {
                EventData data = event.getData();
                if (testId.equals(data.getProperties().get("testId"))) {
                    bodies.add(data.getBodyAsString());
                }
            }
        }
        return bodies;
    }
}
