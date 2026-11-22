package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.postoffice.PostOffice;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.impl.AddressInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Service Bus entity paths compare ignoring case: an attach to any spelling of a declared
 * address resolves onto the spelling the entity was created with.
 */
class ServiceBusEntityNamesTest {

    private static URLClassLoader loader;
    private static Method sourceEntityPath;
    private static Method targetEntityPath;
    private static String serviceBusBrokerPrefix;

    @BeforeAll
    static void loadFromPatchJar() throws Exception {
        loader = PatchJarLoader.open();
        Class<?> support = Class.forName(
                "org.apache.activemq.artemis.protocol.amqp.proton.ServiceBusSessionSupport", true, loader);
        sourceEntityPath = support.getMethod("sourceEntityPath", ActiveMQServer.class, String.class);
        targetEntityPath = support.getMethod("targetEntityPath", ActiveMQServer.class, String.class);
        serviceBusBrokerPrefix = (String) support.getField("SERVICE_BUS_BROKER_PREFIX").get(null);
    }

    @AfterAll
    static void close() throws Exception {
        loader.close();
    }

    private static ActiveMQServer broker(String name, String... addresses) {
        ActiveMQServer server = mock(ActiveMQServer.class);
        Configuration configuration = mock(Configuration.class);
        PostOffice postOffice = mock(PostOffice.class);
        when(server.getConfiguration()).thenReturn(configuration);
        when(configuration.getName()).thenReturn(name);
        when(server.getPostOffice()).thenReturn(postOffice);
        Set<SimpleString> declared = new LinkedHashSet<>();
        for (String address : addresses) {
            declared.add(SimpleString.of(address));
        }
        when(postOffice.getAddresses()).thenReturn(declared);
        when(postOffice.getAddressInfo(any(SimpleString.class))).thenAnswer(invocation -> {
            SimpleString requested = invocation.getArgument(0);
            return declared.contains(requested) ? new AddressInfo(requested) : null;
        });
        return server;
    }

    private static ActiveMQServer serviceBus(String... addresses) {
        return broker(serviceBusBrokerPrefix + "default", addresses);
    }

    private static String target(ActiveMQServer server, String address) throws Exception {
        return (String) targetEntityPath.invoke(null, server, address);
    }

    private static String source(ActiveMQServer server, String address) throws Exception {
        return (String) sourceEntityPath.invoke(null, server, address);
    }

    @Test
    void aSenderToAnotherSpellingOfAQueueReachesTheCreatedQueue() throws Exception {
        ActiveMQServer server = serviceBus("example-case", "example-case/$DeadLetterQueue", "example-case/$management");
        assertEquals("example-case", target(server, "EXAMPLE-CASE"));
        assertEquals("example-case", target(server, "amqps://localhost:5673/Example-Case"));
        assertEquals("example-case/$management", target(server, "EXAMPLE-CASE/$management"));
    }

    @Test
    void aReceiverOnAnotherSpellingResolvesQueuesSubscriptionsAndDeadLetterQueues() throws Exception {
        ActiveMQServer server = serviceBus("orders", "orders/$DeadLetterQueue",
                "Events", "Events/Subscriptions/Worker", "Events/Subscriptions/Worker/$DeadLetterQueue");
        assertEquals("orders", source(server, "ORDERS"));
        assertEquals("orders/$DeadLetterQueue", source(server, "Orders/$deadletterqueue"));
        assertEquals("Events/Subscriptions/Worker", source(server, "events/subscriptions/worker"));
        assertEquals("Events/Subscriptions/Worker/$DeadLetterQueue",
                source(server, "amqps://localhost:5673/EVENTS/SUBSCRIPTIONS/WORKER/$DEADLETTERQUEUE"));
    }

    @Test
    void anExactMatchWinsOverAnotherSpelling() throws Exception {
        ActiveMQServer server = serviceBus("jobs", "JOBS");
        assertEquals("JOBS", target(server, "JOBS"));
        assertEquals("jobs", target(server, "jobs"));
    }

    @Test
    void anAmbiguousOrUnknownPathIsReturnedAsItArrived() throws Exception {
        ActiveMQServer server = serviceBus("jobs", "JOBS");
        assertEquals("Jobs", target(server, "Jobs"));
        assertEquals("missing", target(server, "missing"));
        assertNull(target(server, null));
    }

    @Test
    void theEventHubsBrokerKeepsTheSpellingTheClientSent() throws Exception {
        ActiveMQServer server = broker("floci-az-eventhubs", "eh1");
        assertEquals("EH1", target(server, "EH1"));
        assertEquals("EH1", source(server, "EH1"));
    }
}
