package org.apache.activemq.artemis.protocol.amqp.proton;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.servicebus.ServiceBusConfigGenerator;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The receive path's source address is reduced by broker: a Service Bus broker reduces every
 * scheme-and-host spelling, while the Event Hubs broker keeps the event-hub-only reduction so
 * namespace-carrying URIs stay whole.
 */
class ServiceBusSourceEntityPathTest {

    private static URLClassLoader loader;
    private static Method sourceEntityPath;
    private static String serviceBusBrokerPrefix;

    @BeforeAll
    static void loadFromPatchJar() throws Exception {
        loader = PatchJarLoader.open();
        Class<?> support = Class.forName(
                "org.apache.activemq.artemis.protocol.amqp.proton.ServiceBusSessionSupport", true, loader);
        sourceEntityPath = support.getMethod("sourceEntityPath", ActiveMQServer.class, String.class);
        serviceBusBrokerPrefix = (String) support.getField("SERVICE_BUS_BROKER_PREFIX").get(null);
    }

    @AfterAll
    static void close() throws Exception {
        loader.close();
    }

    private static ActiveMQServer broker(String name) {
        ActiveMQServer server = mock(ActiveMQServer.class);
        Configuration configuration = mock(Configuration.class);
        when(server.getConfiguration()).thenReturn(configuration);
        when(configuration.getName()).thenReturn(name);
        return server;
    }

    private static String resolve(ActiveMQServer server, String address) throws Exception {
        return (String) sourceEntityPath.invoke(null, server, address);
    }

    @Test
    @DisplayName("a Service Bus broker resolves URI-addressed subscriptions and dead-letter queues")
    void serviceBusBroker() throws Exception {
        ActiveMQServer serviceBus = broker(serviceBusBrokerPrefix + "default");
        assertEquals("events/Subscriptions/worker",
                resolve(serviceBus, "amqps://localhost:5673/events/Subscriptions/worker"));
        assertEquals("events/Subscriptions/worker/$DeadLetterQueue",
                resolve(serviceBus, "amqps://localhost:5673/events/subscriptions/worker/$deadletterqueue"));
        assertEquals("jobs/$DeadLetterQueue", resolve(serviceBus, "amqps://localhost:5673/jobs/$DeadLetterQueue"));
        assertEquals("jobs", resolve(serviceBus, "amqps://localhost:5673/jobs"));
    }

    @Test
    @DisplayName("the Event Hubs broker keeps a namespace-carrying URI whole, as before")
    void eventHubsBroker() throws Exception {
        ActiveMQServer eventHubs = broker("floci-az-eventhubs");
        assertEquals("amqp://host/ns/Subscriptions/$Default",
                resolve(eventHubs, "amqp://host/ns/Subscriptions/$Default"));
        assertEquals("eh1", resolve(eventHubs, "amqps://ns.servicebus.windows.net/eh1"));
    }

    @Test
    @DisplayName("an unidentifiable broker gets the event-hub reduction")
    void unknownBroker() throws Exception {
        assertEquals("amqp://host/ns/Subscriptions/$Default",
                resolve(null, "amqp://host/ns/Subscriptions/$Default"));
        assertEquals("amqp://host/ns/Subscriptions/$Default",
                resolve(broker(null), "amqp://host/ns/Subscriptions/$Default"));
    }

    /** The prefix is how the patch recognizes a Service Bus broker: it must match the broker name floci-az writes. */
    @Test
    @DisplayName("the Service Bus broker prefix matches the generated broker name")
    void prefixMatchesGeneratedBrokerName() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(serviceBus.maxDeliveryCount()).thenReturn(10);

        String brokerXml = new ServiceBusConfigGenerator(config).generate("default");
        assertTrue(brokerXml.contains("<name>" + serviceBusBrokerPrefix + "default</name>"), brokerXml);
    }
}
