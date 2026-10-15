package io.floci.az.services.eventhub;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.eventhub.ArtemisConfigGenerator.EntitySpec;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.filter.Filter;
import org.apache.activemq.artemis.core.filter.impl.FilterImpl;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage;
import org.apache.qpid.proton.Proton;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtemisConfigGeneratorTest {

    private static final Pattern DIVERT_NAME = Pattern.compile("<divert name=\"([^\"]+)\"");
    private static final Pattern ADDRESS_NAME = Pattern.compile("<address name=\"([^\"]+)\"");

    private static String brokerXml(String namespace, Map<String, EntitySpec> entities) {
        return new ArtemisConfigGenerator(null).generate(namespace, entities);
    }

    private static List<String> matches(Pattern pattern, String xml) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(xml);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static void assertAllDistinct(List<String> names) {
        Set<String> distinct = new HashSet<>(names);
        assertEquals(names.size(), distinct.size(),
                "divert names collide, so Artemis would skip a binding: " + names);
    }

    /**
     * Azure allows a dot in a hub name and Artemis names should not carry one, so two hubs that
     * differ only there would reduce to the same divert name.
     */
    @Test
    void hubNamesThatSanitizeAlikeKeepDistinctDivertNames() {
        assertAllDistinct(matches(DIVERT_NAME, brokerXml("ns", Map.of(
                "eh.1", new EntitySpec(2, List.of("$Default")),
                "eh-1", new EntitySpec(2, List.of("$Default"))))));
    }

    /**
     * The separator joining the parts of a divert name is itself legal inside a hub or consumer
     * group name, so the parts have to stay distinguishable after they are joined.
     */
    @Test
    void namesThatJoinToTheSameStringKeepDistinctDivertNames() {
        assertAllDistinct(matches(DIVERT_NAME, brokerXml("ns", Map.of(
                "a-to-b", new EntitySpec(1, List.of("c")),
                "a", new EntitySpec(1, List.of("b-to-c"))))));
    }

    /**
     * One topology per hub, not one per spelling of its address.
     *
     * The AMQP layer reduces every address a client can send to down to the entity path, so the
     * partition queues hang off that path alone. Generating a copy per host and scheme gave each
     * spelling its own private hub: a producer on one and a consumer on another read and wrote
     * different queues, while the management node reported a single hub either way.
     */
    @Test
    void partitionQueuesExistOncePerHubNotOncePerAddressSpelling() {
        List<String> partitionZero = matches(ADDRESS_NAME, brokerXml("ns",
                Map.of("eh1", new EntitySpec(4, List.of("$Default", "other")))))
                .stream().filter(a -> a.endsWith("/Partitions/0") && a.contains("ConsumerGroups"))
                .toList();

        assertEquals(List.of("eh1/ConsumerGroups/$Default/Partitions/0",
                             "eh1/ConsumerGroups/other/Partitions/0"),
                     partitionZero.stream().sorted().toList());
    }

    /**
     * The Java SDK sends to a pinned partition by addressing it, so that address needs a queue to
     * attach to. Without one it is auto-created empty and the sends are dropped in silence.
     */
    @Test
    void everyPartitionHasASenderAddress() {
        List<String> addresses =
                matches(ADDRESS_NAME, brokerXml("ns", Map.of("eh1", new EntitySpec(3, List.of("$Default")))));

        for (int partition = 0; partition < 3; partition++) {
            String pinned = ArtemisConfigGenerator.partitionSenderAddress("eh1", partition);
            assertTrue(addresses.contains(pinned), "no sender address for " + pinned);
        }
    }

    /** The generated file must not change between runs, or every namespace restart rewrites it. */
    @Test
    void generatingTwiceProducesTheSameNames() {
        Map<String, EntitySpec> entities =
                Map.of("eh1", new EntitySpec(4, List.of("$Default", "my-consumer-group")));
        assertEquals(matches(DIVERT_NAME, brokerXml("ns", entities)),
                     matches(DIVERT_NAME, brokerXml("ns", entities)));
    }

    /**
     * The hostname family must match the broker's real container name, which carries the Docker
     * resource namespace when one is configured.
     */
    @Test
    void hostnameDivertsUseTheNamespacedContainerName() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("w1ns"));
        String containerName = new EventHubNamespaceManager(config, null, null, null, null).containerName("ns");

        List<String> addresses = matches(ADDRESS_NAME,
                new ArtemisConfigGenerator(config).generate("ns", Map.of("eh1", new EntitySpec(1, List.of("$Default")))));

        assertEquals("floci-az-w1ns-artemis-ns", containerName);
        assertTrue(addresses.contains(ArtemisConfigGenerator.anycastEntityAddress(containerName, "ns", "eh1")),
                "no hostname divert family for " + containerName + ": " + addresses);
        assertFalse(addresses.stream().anyMatch(a -> a.startsWith("amqp://floci-az-artemis-ns/")),
                "hostname family still uses the un-namespaced container name: " + addresses);
    }

    @Test
    void hostnameDivertsWithoutAResourceNamespaceKeepTheirName() {
        List<String> addresses =
                matches(ADDRESS_NAME, brokerXml("ns", Map.of("eh1", new EntitySpec(1, List.of("$Default")))));

        assertTrue(addresses.contains("amqp://floci-az-artemis-ns/ns/eh1"), addresses.toString());
    }

    /** In published endpoint mode clients are told the daemon's host, so it needs the family too. */
    @Test
    void aClientHostGetsTheUriAddressedFamilyAndItsDiverts() {
        Map<String, EntitySpec> entities = Map.of("eh1", new EntitySpec(1, List.of("$Default")));
        String withDaemonHost = new ArtemisConfigGenerator(null).generate("ns", entities, List.of("Docker-Host"));

        List<String> addresses = matches(ADDRESS_NAME, withDaemonHost);
        assertTrue(addresses.contains("amqp://docker-host/ns/eh1"), addresses.toString());
        assertTrue(addresses.contains("amqp://docker-host/ns/eh1/$Default"), addresses.toString());
        assertEquals(matches(DIVERT_NAME, brokerXml("ns", entities)).size() + 1,
                matches(DIVERT_NAME, withDaemonHost).size());
    }

    @Test
    void aClientHostThatIsLocalhostAddsNoDuplicateAddress() {
        Map<String, EntitySpec> entities = Map.of("eh1", new EntitySpec(1, List.of("$Default")));

        assertEquals(matches(ADDRESS_NAME, brokerXml("ns", entities)),
                matches(ADDRESS_NAME, new ArtemisConfigGenerator(null).generate("ns", entities, List.of("localhost"))));
    }

    /**
     * The partition plugin stamps an event's partition as a broker property, which Artemis reads
     * after the application properties and does not send. Each partition divert must still take
     * the events of its own partition and no other.
     */
    @Test
    void partitionDivertsSelectByTheBrokerProperty() throws Exception {
        String xml = brokerXml("ns", Map.of("eh1", new EntitySpec(4, List.of("$Default"))));
        Matcher divert = Pattern.compile(
                "<address>eh1</address><forwarding-address>eh1/ConsumerGroups/\\$Default/Partitions/(\\d+)"
                        + "</forwarding-address><filter string=\"([^\"]+)\"/>").matcher(xml);

        int diverts = 0;
        while (divert.find()) {
            String partition = divert.group(1);
            Filter filter = FilterImpl.createFilter(divert.group(2).replace("&apos;", "'"));
            for (int stamped = 0; stamped < 4; stamped++) {
                assertEquals(partition.equals(Integer.toString(stamped)), filter.match(event(stamped)),
                        "the divert of partition " + partition + " on an event stamped " + stamped);
            }
            diverts++;
        }
        assertEquals(4, diverts, xml);
    }

    /** An event as the partition plugin leaves it: its partition in a broker property only. */
    private static AMQPStandardMessage event(int partition) {
        // Proton's Message: Artemis has a Message type of its own.
        org.apache.qpid.proton.message.Message message = Proton.message();
        message.setMessageId("event-1");
        byte[] buffer = new byte[1024];
        int size = message.encode(buffer, 0, buffer.length);
        AMQPStandardMessage event = new AMQPStandardMessage(0, Arrays.copyOf(buffer, size), null);
        event.createExtraProperties().putSimpleStringProperty(
                SimpleString.of(ArtemisConfigGenerator.PARTITION_PROPERTY), SimpleString.of(Integer.toString(partition)));
        return event;
    }
}
