package io.floci.az.services.eventhub;

import io.floci.az.services.eventhub.ArtemisConfigGenerator.EntitySpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
