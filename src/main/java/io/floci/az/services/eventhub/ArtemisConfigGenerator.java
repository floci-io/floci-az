package io.floci.az.services.eventhub;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.XmlBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates the Artemis broker.xml for an Event Hubs namespace.
 *
 * The broker.xml statically configures two address families, which stay apart deliberately:
 * - the namespace-carrying one, addressed either by path ({@code {namespace}/{entity}}, MULTICAST,
 *   one queue per consumer group, what rhea-promise publishes to) or by URI
 *   ({@code amqp://{host}/{namespace}/{entity}}, ANYCAST, fanned out by exclusive diverts, what
 *   uamqp sends). Both predate partitions, and neither is what an Azure SDK sends.
 * - {@code {entity}} — the partitioned topology the Azure SDKs reach, declared once because
 *   {@link org.apache.activemq.artemis.protocol.amqp.proton.AmqpEntityAddress} reduces every
 *   scheme and host spelling of it to this one path.
 *
 * Pre-configuring topology in broker.xml avoids any dependency on the Jolokia management API,
 * which can take several minutes to start and would otherwise block readiness.
 */
@ApplicationScoped
public class ArtemisConfigGenerator {

    private static final String DEFAULT_CONSUMER_GROUP = "$Default";
    /** A hub configured without an explicit count is one undivided stream. */
    static final int DEFAULT_PARTITION_COUNT = 1;
    /** Azure's own per-hub limit outside dedicated clusters. */
    static final int MAX_PARTITION_COUNT = 32;
    /** Message property carrying the assigned partition; set by the broker plugin, filtered on here. */
    static final String PARTITION_PROPERTY = "floci_partition";
    private static final String CBS_ADDRESS = "$cbs";
    private static final String CBS_INTERCEPT_ADDRESS = "$cbs-intercept";
    private static final String MANAGEMENT_ADDRESS = "$management";
    private static final String MANAGEMENT_INTERCEPT_ADDRESS = "$management-intercept";

    private final EmulatorConfig config;

    @Inject
    public ArtemisConfigGenerator(EmulatorConfig config) {
        this.config = config;
    }

    /** Returns broker.xml for the given namespace and entities (used for dynamic namespace creation). */
    public String generate(String namespace, Map<String, EntitySpec> entities) {
        List<String> hostnames = List.of("localhost", "floci-az-artemis-" + namespace);

        XmlBuilder addresses = new XmlBuilder();
        XmlBuilder diverts = new XmlBuilder();
        // Passed to the partition plugin so it knows how many partitions each hub has.
        StringBuilder partitionSpec = new StringBuilder();

        for (Map.Entry<String, EntitySpec> entry : entities.entrySet()) {
            String entityName = entry.getKey();
            List<String> cgs = entry.getValue().consumerGroups();
            int partitions = entry.getValue().partitionCount();

            if (!partitionSpec.isEmpty()) {
                partitionSpec.append(',');
            }
            partitionSpec.append(entityName).append(':').append(partitions);

            // The namespace-carrying family, addressed by path (multicast) or by URI (anycast).
            // Both predate partitions and neither is what an Azure SDK sends.
            appendMulticastAddress(addresses, namespace + "/" + entityName, cgs);
            for (String hostname : hostnames) {
                appendAnycastTopology(addresses, diverts, hostname, namespace, entityName, cgs);
            }
            // The family the Azure SDKs use, declared once: the AMQP layer reduces every scheme
            // and host spelling of it to this one path.
            appendPartitionTopology(addresses, diverts, entityName, cgs, partitions);
        }
        return buildBrokerXml(addresses, diverts, partitionSpec.toString());
    }

    /** Returns broker.xml using the default namespace/entities from config. */
    public String generate() {
        EmulatorConfig.EventHubConfig eh = config.services().eventHub();
        return generate(eh.defaultNamespace(), parseEntities(eh.entities(), eh.consumerGroups()));
    }

    /** An event hub's partition count and its consumer groups. */
    public record EntitySpec(int partitionCount, List<String> consumerGroups) {}

    /**
     * Parses "eh1:4,eh2:2" and a consumer groups string into entityName → {@link EntitySpec}.
     *
     * A hub named without a count gets {@link #DEFAULT_PARTITION_COUNT}, which keeps the single
     * undivided stream this emulated before partitions existed. The count is now load-bearing —
     * it decides how many queues and diverts each consumer group gets — so it is validated rather
     * than ignored: it must be a positive number, and no larger than {@link #MAX_PARTITION_COUNT},
     * the limit Azure applies outside dedicated clusters. Each partition costs a durable queue per
     * consumer group, so an unbounded count multiplies quietly into thousands of queues.
     */
    public static Map<String, EntitySpec> parseEntities(String entitiesStr, String consumerGroupsStr) {
        Map<String, EntitySpec> result = new LinkedHashMap<>();
        List<String> groups = parseConsumerGroups(consumerGroupsStr);
        if (entitiesStr == null || entitiesStr.isBlank()) {
            result.put("eh1", new EntitySpec(DEFAULT_PARTITION_COUNT, groups));
            return result;
        }
        for (String token : entitiesStr.split(",")) {
            token = token.trim();
            if (token.isEmpty()) continue;
            String[] parts = token.split(":", 2);
            String name = parts[0].trim();
            if (name.isEmpty()) continue;
            result.put(name, new EntitySpec(parsePartitionCount(name, parts), groups));
        }
        return result;
    }

    private static int parsePartitionCount(String entityName, String[] parts) {
        if (parts.length < 2 || parts[1].isBlank()) {
            return DEFAULT_PARTITION_COUNT;
        }
        String raw = parts[1].trim();
        int count;
        try {
            count = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Event hub '" + entityName + "': partition count '" + raw + "' is not a number");
        }
        if (count < 1) {
            throw new IllegalArgumentException(
                    "Event hub '" + entityName + "': partition count must be at least 1, got " + count);
        }
        if (count > MAX_PARTITION_COUNT) {
            throw new IllegalArgumentException(
                    "Event hub '" + entityName + "': partition count " + count + " exceeds the maximum of "
                    + MAX_PARTITION_COUNT);
        }
        return count;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private String buildBrokerXml(XmlBuilder addresses, XmlBuilder diverts, String partitionSpec) {
        XmlBuilder xml = new XmlBuilder()
            .start("configuration", "urn:activemq")
              .start("core", "urn:activemq:core")
                .elem("name", "floci-az-eventhubs")
                .start("acceptors")
                  .startAttr("acceptor", "name", "amqp")
                    .raw("tcp://0.0.0.0:5672?protocols=AMQP"
                        + ";saslMechanisms=MSSBCBS,ANONYMOUS,PLAIN;maxMessageSize=1048576")
                  .end("acceptor")
                  .startAttr("acceptor", "name", "amqps")
                    .raw("tcp://0.0.0.0:5671?protocols=AMQP"
                        + ";sslEnabled=true"
                        + ";keyStorePath=/var/lib/artemis-instance/etc-override/artemis.p12"
                        + ";keyStorePassword=" + ArtemisTlsGenerator.KEYSTORE_PASSWORD
                        + ";keyStoreType=PKCS12"
                        + ";needClientAuth=false"
                        + ";saslMechanisms=MSSBCBS,ANONYMOUS,PLAIN"
                        + ";maxMessageSize=1048576")
                  .end("acceptor")
                .end("acceptors")
                .elem("security-enabled", false)
                .start("broker-plugins")
                  .startAttr("broker-plugin", "class-name",
                          "io.floci.az.artemis.EventHubPartitionPlugin")
                    .selfClose("property", "key", "entities", "value", partitionSpec)
                  .end("broker-plugin")
                .end("broker-plugins")
                .start("address-settings")
                  .startAttr("address-setting", "match", "#")
                    .elem("auto-create-queues", true)
                    .elem("auto-create-addresses", true)
                    .elem("default-address-routing-type", "MULTICAST")
                    .elem("default-queue-routing-type", "MULTICAST")
                  .end("address-setting")
                .end("address-settings")
                .start("addresses")
                  .raw(addresses.build())
                  .startAttr("address", "name", CBS_ADDRESS)
                    .selfClose("multicast")
                  .end("address")
                  .startAttr("address", "name", CBS_INTERCEPT_ADDRESS)
                    .start("anycast")
                      .selfClose("queue", "name", CBS_INTERCEPT_ADDRESS)
                    .end("anycast")
                  .end("address")
                  .startAttr("address", "name", MANAGEMENT_ADDRESS)
                    .selfClose("multicast")
                  .end("address")
                  .startAttr("address", "name", MANAGEMENT_INTERCEPT_ADDRESS)
                    .start("anycast")
                      .selfClose("queue", "name", MANAGEMENT_INTERCEPT_ADDRESS)
                    .end("anycast")
                  .end("address")
                .end("addresses");

        // The CBS divert is unconditional: every Azure SDK puts a token on $cbs before
        // opening entity links, and it is the CbsResponder — attached to the intercept
        // queue — that answers. Without it the put-token reply carries no status-code
        // and the SDK cannot authorize.
        xml.start("diverts")
             .raw(diverts.build())
             .startAttr("divert", "name", "cbs-request-intercept")
               .elem("address", CBS_ADDRESS)
               .elem("forwarding-address", CBS_INTERCEPT_ADDRESS)
               .selfClose("filter", "string", "operation = 'put-token'")
               .elem("exclusive", true)
             .end("divert")
             // Requests only. Without the filter the responder's own replies, which go back on
             // $management, would divert straight back to it in a loop.
             .startAttr("divert", "name", "management-request-intercept")
               .elem("address", MANAGEMENT_ADDRESS)
               .elem("forwarding-address", MANAGEMENT_INTERCEPT_ADDRESS)
               .selfClose("filter", "string", "operation = 'READ'")
               .elem("exclusive", true)
             .end("divert")
           .end("diverts");

        xml.end("core").end("configuration");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n" + xml.build();
    }

    /** MULTICAST address with one queue per consumer group (for rhea-promise / Node.js). */
    private void appendMulticastAddress(XmlBuilder builder, String addr, List<String> consumerGroups) {
        XmlBuilder queues = new XmlBuilder();
        for (String cg : consumerGroups) {
            queues.selfClose("queue", "name", addr + "/" + cg);
        }
        builder
            .startAttr("address", "name", addr)
              .start("multicast")
                .raw(queues.build())
              .end("multicast")
            .end("address");
    }

    /**
     * Appends ANYCAST addresses, durable queues, and exclusive diverts for the Python uamqp SDK,
     * which uses full AMQP URI addressing: {@code amqp://hostname/namespace/entity}.
     */
    private void appendAnycastTopology(XmlBuilder addresses, XmlBuilder diverts,
                                        String hostname, String namespace, String entity,
                                        List<String> consumerGroups) {
        String entityAddr = anycastEntityAddress(hostname, namespace, entity);

        // An explicit (non-durable) queue at the entity address lets the sender link attach.
        // The exclusive diverts below intercept all messages before they reach this queue,
        // so messages flow only to the per-consumer-group durable queues.
        addresses.startAttr("address", "name", entityAddr)
                   .start("anycast")
                     .startAttr("queue", "name", entityAddr)
                       .elem("durable", false)
                     .end("queue")
                   .end("anycast")
                 .end("address");

        for (String cg : consumerGroups) {
            String cgAddr = entityAddr + "/" + cg;
            String divertName = anycastDivertName(hostname, entity, cg);

            addresses.startAttr("address", "name", cgAddr)
                       .start("anycast")
                         .startAttr("queue", "name", cgAddr)
                           .elem("durable", true)
                         .end("queue")
                       .end("anycast")
                     .end("address");

            diverts.startAttr("divert", "name", divertName)
                     .elem("address", entityAddr)
                     .elem("forwarding-address", cgAddr)
                     .elem("exclusive", true)
                   .end("divert");
        }
    }

    /**
     * Appends the partitioned topology for one hub: the addresses a producer sends to and one
     * durable queue per (consumer group, partition) for consumers to attach to.
     *
     * Everything hangs off the bare entity path, once. That is what the AMQP layer reduces every
     * address to ({@link org.apache.activemq.artemis.protocol.amqp.proton.AmqpEntityAddress}), so
     * a Java producer naming {@code eh1} and a Rust consumer naming
     * {@code amqps://ns.servicebus.windows.net/eh1} meet on the same queues. Generating a copy per
     * host and scheme instead gave each spelling its own private hub — the sends were not lost, but
     * only a client using the very same spelling could read them, while the management node
     * reported one hub either way.
     *
     * Two kinds of sender address are declared:
     * <ul>
     *   <li>{@code {entity}} — the hub itself, where the partition is chosen by key or round-robin;
     *   <li>{@code {entity}/Partitions/{id}} — a partition named outright, which is how the Java
     *       SDK sends when the caller pins one. Without it those sends reach an auto-created
     *       address with no queues and are discarded with no error anywhere.
     * </ul>
     *
     * Each partition queue is fed by an exclusive divert filtered on the partition property that
     * {@code EventHubPartitionPlugin} stamps as the message is routed — for the pinned address the
     * plugin reads the partition out of the address itself, so the same filter serves both. A
     * message therefore reaches exactly one partition per consumer group, which is the Event Hubs
     * guarantee: every consumer group sees the whole stream, each event in one partition of it.
     */
    private void appendPartitionTopology(XmlBuilder addresses, XmlBuilder diverts, String entity,
                                         List<String> consumerGroups, int partitionCount) {
        appendSenderAddress(addresses, entity);
        for (int partition = 0; partition < partitionCount; partition++) {
            appendSenderAddress(addresses, partitionSenderAddress(entity, partition));
        }

        for (String cg : consumerGroups) {
            for (int partition = 0; partition < partitionCount; partition++) {
                String partitionAddr =
                        entity + "/ConsumerGroups/" + cg + "/Partitions/" + partition;

                addresses.startAttr("address", "name", partitionAddr)
                           .start("anycast")
                             .startAttr("queue", "name", partitionAddr)
                               .elem("durable", true)
                             .end("queue")
                           .end("anycast")
                         .end("address");

                // One divert per sender address: from the hub, and from the pinned-partition
                // address for this partition. Both are exclusive, so a routed message does not
                // also stay in the sender address's own queue — that queue exists only so the
                // sender link has something to attach to, nothing consumes it, and a copy left
                // there would never be drained. Each consumer group still gets its own copy,
                // because it has its own divert and only the matching partition's filter passes.
                for (String senderAddr :
                        List.of(entity, partitionSenderAddress(entity, partition))) {
                    diverts.startAttr("divert", "name",
                                      divertName(senderAddr, cg, "p" + partition))
                             .elem("address", senderAddr)
                             .elem("forwarding-address", partitionAddr)
                             .selfClose("filter", "string",
                                     PARTITION_PROPERTY + " = '" + partition + "'")
                             .elem("exclusive", true)
                           .end("divert");
                }
            }
        }
    }

    /** The address the Java SDK sends to when the caller pins a partition. */
    static String partitionSenderAddress(String entity, int partition) {
        return entity + "/Partitions/" + partition;
    }

    /**
     * Appends an address a producer attaches to.
     *
     * Its queue is non-durable and nothing consumes it: the exclusive partition diverts take every
     * message before it arrives, and the queue exists only so the sender link has something to
     * attach to.
     */
    private void appendSenderAddress(XmlBuilder addresses, String entityAddr) {
        addresses.startAttr("address", "name", entityAddr)
                   .start("anycast")
                     .startAttr("queue", "name", entityAddr)
                       .elem("durable", false)
                     .end("queue")
                   .end("anycast")
                 .end("address");
    }

    /**
     * The entity address the uamqp topology hangs off, and the name of the divert from it to one
     * consumer group's queue.
     *
     * {@code EventHubNamespaceManager} rebuilds this same topology over Jolokia when it starts a
     * namespace, so it calls these rather than spelling the strings a second time. Two spellings
     * that drift apart do not fail — the diverts are exclusive, so the broker ends up holding two
     * of them over one route and delivers every message twice. The host is lowercased because
     * uamqp lowercases the host portion of the URIs it sends.
     */
    static String anycastEntityAddress(String hostname, String namespace, String entity) {
        return "amqp://" + hostname.toLowerCase(Locale.US) + "/" + namespace + "/" + entity;
    }

    static String anycastDivertName(String hostname, String entity, String consumerGroup) {
        return divertName(hostname, entity, "to", consumerGroup);
    }

    /**
     * A divert name that is readable and cannot collide with another one.
     *
     * Artemis skips a duplicate binding without saying so, which would leave one consumer group's
     * queue receiving nothing while every send still succeeded. Two diverts reducing to the same
     * name is easy to arrange: Azure allows dots in a hub or consumer-group name and Artemis names
     * should not carry them, so {@code eh.1} and {@code eh-1} sanitize alike; and the separator
     * between the parts is legal inside a part, so entity {@code a-to-b} with group {@code c}
     * joins to the same string as entity {@code a} with group {@code b-to-c}.
     *
     * The suffix is what makes the name unique — a hash over the exact parts, built from
     * {@link String#hashCode()}, whose value the language specification pins so the generated
     * broker.xml is the same on every run and every JVM. The readable part is kept in front of it
     * so the file can still be read.
     */
    private static String divertName(String... parts) {
        String readable = String.join("-", parts).replaceAll("[^A-Za-z0-9_-]", "-");
        return readable + "-" + Integer.toHexString(List.of(parts).hashCode());
    }

    private static List<String> parseConsumerGroups(String raw) {
        List<String> groups = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            groups.add(DEFAULT_CONSUMER_GROUP);
            return groups;
        }
        for (String g : raw.split(",")) {
            String trimmed = g.trim();
            if (!trimmed.isEmpty()) {
                groups.add(trimmed);
            }
        }
        if (groups.isEmpty()) {
            groups.add(DEFAULT_CONSUMER_GROUP);
        }
        return groups;
    }
}
