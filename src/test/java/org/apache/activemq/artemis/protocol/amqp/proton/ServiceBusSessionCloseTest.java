package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPSessionCallback;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class ServiceBusSessionCloseTest {
    @Test
    void expiredSessionReturnsUnsettledReferencesAsFailedDeliveries() throws Exception {
        try (var loader = PatchJarLoader.open()) {
            var callback = mock(AMQPSessionCallback.class);
            var consumer = mock(ServerConsumer.class, RETURNS_DEEP_STUBS);
            var type = loader.loadClass("org.apache.activemq.artemis.protocol.amqp.proton.ServiceBusSessionSupport");
            type.getMethod("closeSender", AMQPSessionCallback.class, ServerConsumer.class, ErrorCondition.class)
                    .invoke(null, callback, consumer, new ErrorCondition(
                            Symbol.valueOf("com.microsoft:session-lock-lost"), "The session lock has expired"));
            verify(consumer).close(true);
            verify(consumer.getQueue()).recheckRefCount(callback.getSessionContext());
            verify(callback, never()).closeSender(any());
        }
    }

    @Test
    void gracefulAndOtherLinkClosuresKeepExistingCancellationSemantics() throws Exception {
        try (var loader = PatchJarLoader.open()) {
            var callback = mock(AMQPSessionCallback.class);
            var consumer = mock(ServerConsumer.class);
            var type = loader.loadClass("org.apache.activemq.artemis.protocol.amqp.proton.ServiceBusSessionSupport");
            var close = type.getMethod("closeSender", AMQPSessionCallback.class, ServerConsumer.class, ErrorCondition.class);
            close.invoke(null, callback, consumer, null);
            close.invoke(null, callback, consumer, new ErrorCondition(Symbol.valueOf("amqp:internal-error"), "failure"));
            verify(callback, times(2)).closeSender(consumer);
            verifyNoInteractions(consumer);
        }
    }
}
