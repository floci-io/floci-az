package io.floci.az.artemis;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ServerSession;
import org.apache.activemq.artemis.core.transaction.Transaction;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Assigns each message an Event Hubs partition as it is routed.
 *
 * <p>Artemis has no notion of partitions, so the emulated ones are ordinary queues selected by a
 * filter. This plugin supplies what that filter matches on: it stamps {@value #PARTITION_PROPERTY}
 * with the partition the message belongs to, and the generated broker.xml gives each partition a
 * divert filtered on that value. A message therefore reaches exactly one partition per consumer
 * group — every group sees the whole stream, each event in one partition of it.
 *
 * <p>Assignment follows the Event Hubs rules, in order of precedence:
 * <ol>
 *   <li>an explicitly addressed partition id, honoured as given;</li>
 *   <li>a partition key, hashed so the same key always lands in the same partition;</li>
 *   <li>neither — round-robin across the hub's partitions.</li>
 * </ol>
 *
 * <p>The hash is {@link String#hashCode()}, which the Java language specification pins, so the
 * mapping is stable across runs and JVMs. It deliberately does not reproduce Azure's own hash:
 * that is undocumented, and client code depends on the guarantee (same key, same partition;
 * ordering within a partition) rather than on which index a key lands in.
 */
public final class EventHubPartitionPlugin implements ActiveMQServerPlugin {

    /** Property the generated partition diverts filter on. */
    public static final String PARTITION_PROPERTY = "floci_partition";
    /** Set by senders that pin a message to a partition, and echoed back to consumers. */
    private static final String PARTITION_ID_ANNOTATION = "x-opt-partition-id";
    /** Set by senders that supply a partition key. */
    private static final String PARTITION_KEY_ANNOTATION = "x-opt-partition-key";
    /** Stream position, as consumers read it back. */
    private static final String OFFSET_ANNOTATION = "x-opt-offset";
    private static final String SEQUENCE_NUMBER_ANNOTATION = "x-opt-sequence-number";
    private static final String ENQUEUED_TIME_ANNOTATION = "x-opt-enqueued-time";
    /**
     * Stream position, as a start-position selector matches it. The names are duplicated in
     * {@code EventHubFilterSupport}, which rewrites the selectors onto them: that class patches
     * the AMQP protocol jar and this one is a broker plugin, so neither can see the other's
     * constants.
     */
    private static final String OFFSET_PROPERTY = "floci_offset";
    private static final String SEQUENCE_NUMBER_PROPERTY = "floci_sequence_number";
    private static final String ENQUEUED_TIME_PROPERTY = "floci_enqueued_time";
    /** Segment a producer address carries when the caller pinned a partition. */
    private static final String PARTITIONS_SEGMENT = "/Partitions/";
    /** Plugin property: "eh1:4,eh2:2". */
    private static final String ENTITIES_PROPERTY = "entities";

    private static final System.Logger LOG =
            System.getLogger(EventHubPartitionPlugin.class.getName());

    private final Map<String, Integer> partitionCounts = new HashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> roundRobin = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    @Override
    public void init(Map<String, String> properties) {
        String entities = properties.get(ENTITIES_PROPERTY);
        if (entities == null || entities.isBlank()) {
            return;
        }
        for (String token : entities.split(",")) {
            String[] parts = token.trim().split(":", 2);
            if (parts.length != 2 || parts[0].isBlank()) {
                continue;
            }
            try {
                partitionCounts.put(parts[0].trim(), Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Ignoring malformed partition count in '" + token + "'");
            }
        }
    }

    /**
     * Stamps the partition before the message is routed.
     *
     * <p>It has to be {@code beforeSend} rather than the more obvious {@code beforeMessageRoute}.
     * Despite the name, {@code beforeMessageRoute} fires inside {@code PostOfficeImpl.route}
     * <em>after</em> the bindings have already been resolved — diverts and their filters are
     * evaluated in {@code simpleRoute} further up, so a property set there is too late to steer
     * routing and the partition diverts never match. {@code beforeSend} runs in
     * {@code ServerSessionImpl.doSend} before {@code postOffice.route} is called at all.
     */
    @Override
    public void beforeSend(ServerSession session, Transaction tx, Message message,
                           boolean direct, boolean noAutoCreateQueue) {
        String address = message.getAddress();
        if (address == null) {
            return;
        }
        String hub = hubOf(address);
        int partitionCount = partitionCounts.getOrDefault(hub, 1);
        // A partition named in the address outranks everything: the caller pinned it, and the
        // address is how the Java SDK says so. Otherwise pick by id, key or round-robin. Even a
        // single-partition hub is stamped, because its divert filters on this like any other.
        int pinnedByAddress = partitionInAddress(address);
        int partition = pinnedByAddress >= 0 && pinnedByAddress < partitionCount ? pinnedByAddress
                : partitionCount <= 1 ? 0
                : choosePartition(message, hub, partitionCount);
        // Stamped as a string, and compared as one by the generated divert filters. That mirrors
        // the CBS divert, the one filter on an AMQP application property already known to work
        // here.
        message.putStringProperty(PARTITION_PROPERTY, Integer.toString(partition));
        // Keyed by the hub, not the address: a send pinned to {hub}/Partitions/{id} and one to
        // {hub} land in the same partition queue, so they have to draw from the same counter or
        // consumers would see the offset go backwards.
        stampStreamPosition(message, hub, partition);
        // An AMQP message serves its properties from its encoded form, so a property set here is
        // invisible to the divert filters until the message is re-encoded.
        message.reencode();
    }

    /**
     * Stamps the message's position in its partition's stream, twice over.
     *
     * <p>The annotations are what a consumer reads back: the SDKs take the offset and sequence
     * number of the last event they handled from {@code x-opt-offset} and
     * {@code x-opt-sequence-number}, and store them as the checkpoint they later resume from.
     *
     * <p>The properties are what the broker matches a resume point against. A start position
     * reaches Artemis as a selector over those same annotations, which the AMQP patch rewrites
     * onto these names — hyphens are not legal in a selector identifier, and Artemis compares a
     * number to a quoted constant only under a thread-local flag, so a parseable selector needs
     * both an underscored name and a numeric value to match.
     *
     * <p>Offsets and sequence numbers are the same counter here: Artemis keeps neither, and a
     * consumer only needs them to be monotonic per partition for a resume point to mean anything.
     */
    private void stampStreamPosition(Message message, String hub, int partition) {
        long sequence = sequences
                .computeIfAbsent(hub + "#" + partition, k -> new AtomicLong())
                .getAndIncrement();
        long enqueuedTime = System.currentTimeMillis();

        message.setAnnotation(SimpleString.of(PARTITION_ID_ANNOTATION), Integer.toString(partition));
        message.setAnnotation(SimpleString.of(OFFSET_ANNOTATION), Long.toString(sequence));
        message.setAnnotation(SimpleString.of(SEQUENCE_NUMBER_ANNOTATION), sequence);
        message.setAnnotation(SimpleString.of(ENQUEUED_TIME_ANNOTATION), new Date(enqueuedTime));

        message.putLongProperty(OFFSET_PROPERTY, sequence);
        message.putLongProperty(SEQUENCE_NUMBER_PROPERTY, sequence);
        message.putLongProperty(ENQUEUED_TIME_PROPERTY, enqueuedTime);
    }

    private int choosePartition(Message message, String hub, int partitionCount) {
        Object pinned = addressingHint(message, PARTITION_ID_ANNOTATION);
        if (pinned != null) {
            try {
                int id = Integer.parseInt(pinned.toString().trim());
                if (id >= 0 && id < partitionCount) {
                    return id;
                }
                LOG.log(System.Logger.Level.WARNING,
                        "Partition id " + id + " is outside 0.." + (partitionCount - 1)
                        + " for " + hub + "; falling back to the partition key");
            } catch (NumberFormatException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Ignoring non-numeric partition id '" + pinned + "' on " + hub);
            }
        }

        Object key = addressingHint(message, PARTITION_KEY_ANNOTATION);
        if (key != null) {
            return Math.floorMod(key.toString().hashCode(), partitionCount);
        }

        return Math.floorMod(
                roundRobin.computeIfAbsent(hub, a -> new AtomicInteger()).getAndIncrement(),
                partitionCount);
    }

    /**
     * Reads a sender's partition id or key, wherever the client put it.
     *
     * The Azure SDKs send both as message annotations, and an annotation is not an application
     * property: {@code getObjectProperty} looks only at the latter, so reading it alone loses
     * every partition key and quietly round-robins instead. Some clients do set an application
     * property of the same name, so that stays as the fallback.
     */
    private static Object addressingHint(Message message, String name) {
        Object annotation = message.getAnnotation(SimpleString.of(name));
        return annotation != null ? annotation : message.getObjectProperty(name);
    }

    /** The partition a {@code {hub}/Partitions/{id}} address pins, or -1 if it pins none. */
    private static int partitionInAddress(String address) {
        int marker = address.lastIndexOf(PARTITIONS_SEGMENT);
        if (marker < 0 || marker + PARTITIONS_SEGMENT.length() >= address.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(address.substring(marker + PARTITIONS_SEGMENT.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The hub an address names, whether or not it pins a partition.
     *
     * The AMQP layer has already reduced the address to a path, so the hub is the whole of it
     * minus a trailing {@code /Partitions/{id}}.
     */
    private static String hubOf(String address) {
        int marker = address.lastIndexOf(PARTITIONS_SEGMENT);
        return partitionInAddress(address) >= 0 ? address.substring(0, marker) : address;
    }
}
