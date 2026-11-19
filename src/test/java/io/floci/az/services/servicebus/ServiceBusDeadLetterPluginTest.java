package io.floci.az.services.servicebus;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.Pair;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.RoutingContext;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;
import org.apache.activemq.artemis.core.settings.HierarchicalRepository;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Artemis dead-letters a message past its delivery attempts in its queue's redelivery check. The
 * copy it routes to the dead-letter address carries the reason Azure gives it; a client's
 * explicit dead-letter, which moves the message without that check, is left as it is.
 */
class ServiceBusDeadLetterPluginTest {

    private static final String ENTITY = "orders";
    private static final String DEAD_LETTER_ADDRESS = "orders/$deadletterqueue";

    private URLClassLoader loader;
    private ActiveMQServerPlugin plugin;
    private final Queue queue = mock(Queue.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void loadPlugin() throws Exception {
        URL jar = getClass().getResource(ServiceBusNamespaceManager.ARTEMIS_EXTENSION_RESOURCE);
        assertNotNull(jar, "Jar not found: " + ServiceBusNamespaceManager.ARTEMIS_EXTENSION_RESOURCE
                + "; the build writes it, so run the build");
        loader = new URLClassLoader(new URL[]{jar}, getClass().getClassLoader());
        Class<?> type = Class.forName("io.floci.az.artemis.ServiceBusDeadLetterPlugin", true, loader);
        plugin = (ActiveMQServerPlugin) type.getDeclaredConstructor().newInstance();

        AddressSettings entitySettings = new AddressSettings()
                .setDeadLetterAddress(SimpleString.of(DEAD_LETTER_ADDRESS))
                .setMaxDeliveryAttempts(3);
        HierarchicalRepository<AddressSettings> repository = mock(HierarchicalRepository.class);
        when(repository.getMatch(ENTITY)).thenReturn(entitySettings);
        ActiveMQServer server = mock(ActiveMQServer.class);
        when(server.getAddressSettingsRepository()).thenReturn(repository);
        plugin.registered(server);
    }

    @AfterEach
    void closeLoader() throws Exception {
        loader.close();
    }

    @Test
    void aMessageDeadLetteredPastItsDeliveryAttemptsCarriesMaxDeliveryCountExceeded() throws Exception {
        AMQPStandardMessage copy = deadLetterCopy(Map.of("custom", "preserved"));

        routeFromRedeliveryCheck(copy);

        assertEquals("MaxDeliveryCountExceeded", copy.getObjectProperty("DeadLetterReason"));
        assertEquals("Message could not be consumed after 3 delivery attempts.",
                copy.getObjectProperty("DeadLetterErrorDescription"));
        assertEquals("preserved", copy.getObjectProperty("custom"));
        Message received = copy.copy(7);
        assertEquals("MaxDeliveryCountExceeded", received.getObjectProperty("DeadLetterReason"),
                "the reason survives the re-encode a delivery reads");
    }

    @Test
    void anExplicitDeadLetterKeepsTheReasonTheClientGaveOrDidNotGive() throws Exception {
        AMQPStandardMessage withoutReason = deadLetterCopy(Map.of());
        plugin.beforeMessageRoute(withoutReason, mock(RoutingContext.class), false, false);
        assertNull(withoutReason.getObjectProperty("DeadLetterReason"));
        assertNull(withoutReason.getObjectProperty("DeadLetterErrorDescription"));

        AMQPStandardMessage withReason = deadLetterCopy(Map.of("DeadLetterReason", "poison"));
        plugin.beforeMessageRoute(withReason, mock(RoutingContext.class), false, false);
        assertEquals("poison", withReason.getObjectProperty("DeadLetterReason"));
    }

    @Test
    void aReasonAlreadyOnTheMessageIsNotOverwritten() throws Exception {
        AMQPStandardMessage copy = deadLetterCopy(Map.of(
                "DeadLetterReason", "poison", "DeadLetterErrorDescription", "invalid payload"));

        routeFromRedeliveryCheck(copy);

        assertEquals("poison", copy.getObjectProperty("DeadLetterReason"));
        assertEquals("invalid payload", copy.getObjectProperty("DeadLetterErrorDescription"));
    }

    @Test
    void aCopyRoutedAnywhereButTheDeadLetterAddressIsLeftAlone() throws Exception {
        AMQPStandardMessage divertCopy = deadLetterCopy(Map.of());
        divertCopy.setAddress("orders/Subscriptions/audit");

        routeFromRedeliveryCheck(divertCopy);

        assertNull(divertCopy.getObjectProperty("DeadLetterReason"));
    }

    /** Routes the copy from inside {@link Queue#checkRedelivery}, as Artemis's move does. */
    private void routeFromRedeliveryCheck(Message copy) throws Exception {
        doAnswer(call -> {
            plugin.beforeMessageRoute(copy, mock(RoutingContext.class), false, false);
            return new Pair<>(false, true);
        }).when(queue).checkRedelivery(any(), anyLong(), anyBoolean());

        queue.checkRedelivery(mock(MessageReference.class), System.currentTimeMillis(), false);
    }

    /** The copy QueueImpl.move routes: addressed to the dead-letter address, naming its entity. */
    private static AMQPStandardMessage deadLetterCopy(Map<String, Object> properties) {
        // Qualify Proton Message to distinguish it from the Artemis Message the plugin routes.
        org.apache.qpid.proton.message.Message encoded = Proton.message();
        encoded.setBody(new AmqpValue("payload"));
        if (!properties.isEmpty()) {
            encoded.setApplicationProperties(new ApplicationProperties(properties));
        }
        byte[] buffer = new byte[1024];
        int size = encoded.encode(buffer, 0, buffer.length);
        AMQPStandardMessage copy = new AMQPStandardMessage(0, Arrays.copyOf(buffer, size), null);
        copy.setMessageID(42L);
        copy.setAddress(DEAD_LETTER_ADDRESS);
        copy.setBrokerProperty(Message.HDR_ORIGINAL_ADDRESS, SimpleString.of(ENTITY));
        return copy;
    }
}
