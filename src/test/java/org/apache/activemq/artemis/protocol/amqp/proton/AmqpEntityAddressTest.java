package org.apache.activemq.artemis.protocol.amqp.proton;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The address reduction sits on the AMQP paths that Event Hubs and Service Bus both use, so these
 * pin what it must leave alone as much as what it rewrites.
 */
class AmqpEntityAddressTest {

    private static URLClassLoader loader;
    private static Method toEntityPath;
    private static Method toServiceBusEntityPath;

    @BeforeAll
    static void loadFromPatchJar() throws Exception {
        loader = PatchJarLoader.open();
        toEntityPath = Class.forName(
                        "org.apache.activemq.artemis.protocol.amqp.proton.AmqpEntityAddress",
                        true, loader)
                .getMethod("toEntityPath", String.class);
        toServiceBusEntityPath = toEntityPath.getDeclaringClass()
                .getMethod("toServiceBusEntityPath", String.class);
    }

    @AfterAll
    static void close() throws Exception {
        loader.close();
    }

    private static String reduce(String address) throws Exception {
        return (String) toEntityPath.invoke(null, address);
    }

    private static String reduceServiceBus(String address) throws Exception {
        return (String) toServiceBusEntityPath.invoke(null, address);
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

    /**
     * On the Event Hubs broker a host-carrying path names the namespace family, and an event hub
     * may well be called {@code Subscriptions}. The event-hub reduction must keep that host; the
     * Service Bus reduction runs on Service Bus brokers only.
     */
    @Test
    @DisplayName("an event hub named Subscriptions keeps its host")
    void leavesAnEventHubNamedSubscriptionsAlone() throws Exception {
        assertEquals("amqp://host/ns/Subscriptions/$Default", reduce("amqp://host/ns/Subscriptions/$Default"));
        assertEquals("amqp://host/ns/Subscriptions/group1", reduce("amqp://host/ns/Subscriptions/group1"));
    }

    /**
     * The Python and Rust Service Bus SDKs name every entity under a scheme and host. Left whole,
     * the multi-segment paths match no address and every subscription or dead-letter receive
     * fails with {@code AMQ119010: source address does not exist}.
     */
    @Test
    @DisplayName("on a Service Bus broker every scheme-and-host spelling is reduced to the entity path")
    void reducesServiceBusAddresses() throws Exception {
        assertEquals("events/Subscriptions/worker",
                reduceServiceBus("amqps://localhost:5673/events/Subscriptions/worker"));
        assertEquals("topic1/Subscriptions/sub1",
                reduceServiceBus("sb://ns.servicebus.windows.net/topic1/Subscriptions/sub1"));
        assertEquals("topic1/Subscriptions/sub1/$DeadLetterQueue",
                reduceServiceBus("amqps://ns.servicebus.windows.net/topic1/Subscriptions/sub1/$DeadLetterQueue"));
        assertEquals("queue1/$DeadLetterQueue",
                reduceServiceBus("amqps://ns.servicebus.windows.net/queue1/$DeadLetterQueue"));
        assertEquals("queue1", reduceServiceBus("amqps://ns.servicebus.windows.net/queue1"));
    }

    /** Case is left as sent: the receive path normalizes it afterwards, as it does hostless paths. */
    @Test
    @DisplayName("the Service Bus reduction strips only the scheme and host")
    void serviceBusReductionKeepsThePathAsSent() throws Exception {
        assertEquals("Topic1/subscriptions/Sub1",
                reduceServiceBus("amqps://ns.servicebus.windows.net/Topic1/subscriptions/Sub1"));
        assertEquals("queue1/$deadletterqueue",
                reduceServiceBus("amqps://ns.servicebus.windows.net//queue1/$deadletterqueue"));
    }

    @Test
    @DisplayName("the Service Bus reduction passes hostless and broker addresses through")
    void serviceBusReductionLeavesHostlessAddressesAlone() throws Exception {
        assertEquals("queue1/$DeadLetterQueue", reduceServiceBus("queue1/$DeadLetterQueue"));
        assertEquals("topic1/Subscriptions/sub1", reduceServiceBus("/topic1/Subscriptions/sub1"));
        assertEquals("$cbs", reduceServiceBus("$cbs"));
        assertEquals("amqps://ns.servicebus.windows.net", reduceServiceBus("amqps://ns.servicebus.windows.net"));
        assertEquals("amqps://ns.servicebus.windows.net/", reduceServiceBus("amqps://ns.servicebus.windows.net/"));
        assertNull(reduceServiceBus(null));
    }
}
