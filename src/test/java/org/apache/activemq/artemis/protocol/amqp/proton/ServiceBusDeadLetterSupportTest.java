package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.core.paging.PagingStore;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URLClassLoader;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ServiceBusDeadLetterSupportTest {

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void rejectionBalancesTheRoutedSourceCharge(boolean properties, boolean metadata) throws Exception {
        // Qualify Proton Message to distinguish it from the Artemis Message under test.
        org.apache.qpid.proton.message.Message encoded = Proton.message();
        encoded.setBody(new AmqpValue("payload"));
        if (properties) {
            encoded.setApplicationProperties(new ApplicationProperties(Map.of("custom", "preserved")));
        }
        byte[] bytes = new byte[4096];
        int length = encoded.encode(bytes, 0, bytes.length);
        Message message = new AMQPStandardMessage(0, Arrays.copyOf(bytes, length), null);
        PagingStore source = mock(PagingStore.class);
        AtomicLong addressSize = new AtomicLong();
        doAnswer(call -> {
            addressSize.addAndGet(call.getArgument(0, Integer.class));
            return null;
        })
                .when(source).addSize(anyInt(), eq(false));
        message.setOwner(source);
        message.refUp();
        source.addSize(message.getMemoryEstimate(), false);
        message.routed();
        Rejected rejected = new Rejected();
        ErrorCondition error = new ErrorCondition();
        error.setInfo(metadata ? Map.of(Symbol.valueOf("DeadLetterReason"), "poison") : Map.of());
        rejected.setError(error);
        try (URLClassLoader loader = PatchJarLoader.open()) {
            Class<?> support = Class.forName(
                    "org.apache.activemq.artemis.protocol.amqp.proton.ServiceBusDeadLetterSupport", true, loader);
            support.getMethod("apply", Message.class, Rejected.class).invoke(null, message, rejected);
        }
        // Native QueueImpl.move copies to the DLQ before refDown removes the source charge.
        Message copy = message.copy(2, true);
        if (properties) {
            assertEquals("preserved", copy.getObjectProperty("custom"));
        }
        if (metadata) {
            assertEquals("poison", copy.getObjectProperty("DeadLetterReason"));
        }
        assertEquals(0, message.refDown());
        source.addSize(-message.getMemoryEstimate(), false);
        assertEquals(0, addressSize.get(), "The last source reference must release exactly its charge");
    }

    @Test
    void convertsUnsupportedDeadLetterProperties() throws Exception {
        Message message = mock(Message.class);
        Rejected rejected = new Rejected();
        ErrorCondition error = new ErrorCondition();
        Instant modifiedAt = Instant.parse("2026-08-28T00:00:00Z");
        error.setInfo(Map.of(
                Symbol.valueOf("modified-at"), Date.from(modifiedAt),
                Symbol.valueOf("attempt"), 3));
        rejected.setError(error);

        try (URLClassLoader loader = PatchJarLoader.open()) {
            Class<?> support = Class.forName(
                    "org.apache.activemq.artemis.protocol.amqp.proton.ServiceBusDeadLetterSupport",
                    true,
                    loader);
            support.getMethod("apply", Message.class, Rejected.class)
                    .invoke(null, message, rejected);
        }

        verify(message).putObjectProperty("modified-at", modifiedAt.toString());
        verify(message).putObjectProperty("attempt", 3);
        verify(message).reencode();
    }
}
