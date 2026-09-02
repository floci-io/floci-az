package org.apache.activemq.artemis.protocol.amqp.proton;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The address reduction sits on the AMQP paths that Event Hubs and Service Bus both use, so these
 * pin what it must leave alone as much as what it rewrites.
 */
class AmqpEntityAddressTest {

    private static URLClassLoader loader;
    private static Method toEntityPath;

    @BeforeAll
    static void loadFromPatchJar() throws Exception {
        Path patchJar = Path.of("target", "classes", "artemis",
                "artemis-amqp-protocol-2.44.0-floci-az-artemis-amqp-patch.jar");
        loader = new URLClassLoader(new URL[]{patchJar.toUri().toURL()},
                AmqpEntityAddressTest.class.getClassLoader());
        toEntityPath = Class.forName(
                        "org.apache.activemq.artemis.protocol.amqp.proton.AmqpEntityAddress",
                        true, loader)
                .getMethod("toEntityPath", String.class);
    }

    @AfterAll
    static void close() throws Exception {
        loader.close();
    }

    private static String reduce(String address) throws Exception {
        return (String) toEntityPath.invoke(null, address);
    }

    /**
     * The patched send and receive paths each stripped a leading slash before this existed, and
     * every client that sends one — Service Bus among them — depends on it. Dropping the strip
     * leaves the address matching nothing, which the broker reports as
     * {@code AMQ119010: source address does not exist}.
     */
    @Test
    @DisplayName("a leading slash is always stripped, scheme or no scheme")
    void stripsLeadingSlash() throws Exception {
        assertEquals("queue1", reduce("/queue1"));
        assertEquals("topic1/Subscriptions/sub1", reduce("/topic1/Subscriptions/sub1"));
        assertEquals("queue1", reduce("queue1"));
    }

    @Test
    @DisplayName("an event hub is reduced to its entity path, whatever the scheme and host")
    void reducesEventHubAddresses() throws Exception {
        assertEquals("eh1", reduce("amqps://emulatorNs1.servicebus.windows.net/eh1"));
        assertEquals("eh1", reduce("amqp://localhost/eh1"));
        assertEquals("eh1/Partitions/2", reduce("amqps://ns.servicebus.windows.net/eh1/Partitions/2"));
        assertEquals("eh1/ConsumerGroups/$Default/Partitions/0",
                reduce("amqps://ns.servicebus.windows.net/eh1/ConsumerGroups/$Default/Partitions/0"));
    }

    /**
     * {@code {namespace}/{entity}} is the multicast address path-addressing clients publish to,
     * and {@code amqp://host/{namespace}/{entity}} is the anycast one with diverts behind it.
     * Reducing the second onto the first merges two topologies that exist to behave differently.
     */
    @Test
    @DisplayName("a namespace-carrying path keeps its host")
    void leavesTheNamespaceFamilyAlone() throws Exception {
        assertEquals("amqp://localhost/emulatorNs1/eh1", reduce("amqp://localhost/emulatorNs1/eh1"));
        assertEquals("amqp://localhost/emulatorNs1/eh1/$Default",
                reduce("amqp://localhost/emulatorNs1/eh1/$Default"));
    }

    /** Service Bus entity paths are not event-hub-shaped and must survive untouched. */
    @Test
    @DisplayName("a Service Bus subscription path keeps its host")
    void leavesServiceBusAddressesAlone() throws Exception {
        assertEquals("sb://ns.servicebus.windows.net/topic1/Subscriptions/sub1",
                reduce("sb://ns.servicebus.windows.net/topic1/Subscriptions/sub1"));
        assertEquals("queue1/$DeadLetterQueue", reduce("queue1/$DeadLetterQueue"));
    }

    @Test
    @DisplayName("the broker's own addresses and a hostless address pass through")
    void leavesBrokerAddressesAlone() throws Exception {
        assertEquals("$cbs", reduce("$cbs"));
        assertEquals("$management", reduce("$management"));
        assertEquals("amqps://ns.servicebus.windows.net", reduce("amqps://ns.servicebus.windows.net"));
        assertNull(reduce(null));
    }
}
