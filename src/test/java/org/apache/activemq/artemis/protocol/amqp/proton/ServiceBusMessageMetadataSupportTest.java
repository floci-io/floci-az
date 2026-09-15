package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPMessage;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.UnsignedInteger;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.amqp.messaging.Header;
import org.apache.qpid.proton.amqp.messaging.MessageAnnotations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceBusMessageMetadataSupportTest {
    private static final long ENQUEUED_TIME = 1_789_200_000_000L;
    private static final Symbol SEQUENCE = Symbol.valueOf("x-opt-sequence-number");
    private static final Symbol ENQUEUED = Symbol.valueOf("x-opt-enqueued-time");

    @ParameterizedTest
    @ValueSource(strings = {"peeklock", "session"})
    void streamedDeliveryCountDoesNotRequireAnIndividualMessageLock(String kind) throws Exception {
        try (Fixture fixture = new Fixture(kind)) {
            Header original = new Header();
            original.setDurable(true);
            original.setTtl(UnsignedInteger.valueOf(60_000));
            for (Header input : new Header[]{null, original}) {
                when(fixture.reference.getDeliveryCount()).thenReturn(1);
                Header first = fixture.header(input);
                assertNotNull(first);
                assertEquals(UnsignedInteger.ZERO, first.getDeliveryCount());
                when(fixture.reference.getDeliveryCount()).thenReturn(2);
                Header second = fixture.header(input);
                assertEquals(UnsignedInteger.valueOf(1), second.getDeliveryCount());
                if (input != null) {
                    assertNotSame(input, second);
                    assertEquals(input.getDurable(), second.getDurable());
                    assertEquals(input.getTtl(), second.getTtl());
                    assertNull(input.getDeliveryCount());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realEncodedDeliveryPreservesPayloadAndStoredMessage(boolean locked) throws Exception {
        var original = Proton.message();
        original.setMessageId("message-id");
        original.setCorrelationId("correlation-id");
        original.setContentType("text/plain");
        original.setBody(new AmqpValue("payload"));
        original.setApplicationProperties(new ApplicationProperties(
                Map.of("custom-text", "preserved", "custom-number", 7)));
        original.setMessageAnnotations(new MessageAnnotations(Map.of(
                SEQUENCE, -1L, ENQUEUED, new Date(0), Symbol.valueOf("x-opt-ingress-time"), ENQUEUED_TIME)));
        byte[] encoded = new byte[4096];
        int size = original.encode(encoded, 0, encoded.length);
        AMQPMessage stored = new AMQPStandardMessage(0, Arrays.copyOf(encoded, size), null);

        try (Fixture fixture = new Fixture("peeklock")) {
            if (locked) {
                Class<?> deadlineType = Class.forName(
                        "org.apache.activemq.artemis.protocol.amqp.proton.ServiceBusMessageLockSupport$Deadline",
                        true, fixture.loader);
                var constructor = deadlineType.getDeclaredConstructor(long.class);
                constructor.setAccessible(true);
                doReturn(constructor.newInstance(ENQUEUED_TIME + 60_000))
                        .when(fixture.reference).getProtocolData(deadlineType);
            }
            AMQPMessage delivery = (AMQPMessage) fixture.type.getMethod(
                    "forDelivery", AMQPMessage.class, MessageReference.class)
                    .invoke(null, stored, fixture.reference);
            var buffer = delivery.getSendBuffer(1, fixture.reference);
            byte[] wire = new byte[buffer.remaining()];
            buffer.get(wire);
            var received = Proton.message();
            received.decode(wire, 0, wire.length);
            assertEquals("payload", ((AmqpValue) received.getBody()).getValue());
            assertEquals("message-id", received.getMessageId());
            assertEquals("correlation-id", received.getCorrelationId());
            assertEquals("text/plain", received.getContentType());
            assertEquals(original.getApplicationProperties().getValue(), received.getApplicationProperties().getValue());
            assertEquals(42L, received.getMessageAnnotations().getValue().get(SEQUENCE));
            assertEquals(new Date(ENQUEUED_TIME), received.getMessageAnnotations().getValue().get(ENQUEUED));
            assertEquals(locked ? new Date(ENQUEUED_TIME + 60_000) : null,
                    received.getMessageAnnotations().getValue().get(Symbol.valueOf("x-opt-locked-until")));
            assertNotSame(stored, delivery);
            assertEquals(-1L, stored.getMessageAnnotations().getValue().get(SEQUENCE));
            assertEquals(new Date(0), stored.getMessageAnnotations().getValue().get(ENQUEUED));
            assertFalse(stored.getMessageAnnotations().getValue().containsKey(Symbol.valueOf("x-opt-locked-until")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"peeklock", "session"})
    void standardDeliveryUsesBrokerValuesWithoutChangingStoredMessage(String kind) throws Exception {
        try (Fixture fixture = new Fixture(kind)) {
            AMQPMessage copy = mock(AMQPMessage.class);
            when(fixture.message.copy()).thenReturn(copy);
            MessageAnnotations original = new MessageAnnotations(Map.of(SEQUENCE, -1L, ENQUEUED, new Date(0)));
            when(fixture.message.getMessageAnnotations()).thenReturn(original);

            assertSame(copy, fixture.type.getMethod("forDelivery", AMQPMessage.class, MessageReference.class)
                    .invoke(null, fixture.message, fixture.reference));

            verify(copy).setAnnotation(SimpleString.of(SEQUENCE.toString()), 42L);
            verify(copy).setAnnotation(SimpleString.of(ENQUEUED.toString()), new Date(ENQUEUED_TIME));
            verify(copy).reencode();
            verify(fixture.message, never()).setAnnotation(any(), any());
            verify(fixture.message, never()).reencode();
            assertEquals(-1L, original.getValue().get(SEQUENCE));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"peeklock", "session"})
    void streamedMetadataNeedsNoMessageDeadlineAndPreservesOtherAnnotations(String kind) throws Exception {
        try (Fixture fixture = new Fixture(kind)) {
            Symbol custom = Symbol.valueOf("custom");
            MessageAnnotations original = new MessageAnnotations(Map.of(custom, "kept"));
            MessageAnnotations result = fixture.annotations(original);
            assertEquals(42L, result.getValue().get(SEQUENCE));
            assertEquals(new Date(ENQUEUED_TIME), result.getValue().get(ENQUEUED));
            assertEquals("kept", result.getValue().get(custom));
            assertFalse(result.getValue().containsKey(Symbol.valueOf("x-opt-locked-until")));
            assertEquals(Map.of(custom, "kept"), original.getValue());
            verify(fixture.message, never()).copy();
            verify(fixture.message, never()).getBody();
        }
    }

    @Test
    void brokerTimePrecedesClientCreationTimeAndHasLegacyFallback() throws Exception {
        try (Fixture fixture = new Fixture("session")) {
            when(fixture.message.getTimestamp()).thenReturn(1L);
            assertEquals(new Date(ENQUEUED_TIME), fixture.annotations(null).getValue().get(ENQUEUED));
            when(fixture.message.getIngressTimestamp()).thenReturn(null);
            assertEquals(new Date(1L), fixture.annotations(null).getValue().get(ENQUEUED));
            when(fixture.message.getTimestamp()).thenReturn(0L);
            InvocationTargetException error = assertThrows(InvocationTargetException.class, () -> fixture.annotations(null));
            assertInstanceOf(IllegalStateException.class, error.getCause());
        }
    }

    @Test
    void nonServiceBusDeliveriesRemainUnchanged() throws Exception {
        try (Fixture fixture = new Fixture("session")) {
            when(fixture.reference.getQueue().getUser()).thenReturn(null);
            MessageAnnotations original = new MessageAnnotations(Map.of(SEQUENCE, 99L));
            assertSame(original, fixture.annotations(original));
            Header header = new Header();
            assertSame(header, fixture.header(header));
            assertNull(fixture.header(null));
            assertSame(fixture.message, fixture.type.getMethod("forDelivery", AMQPMessage.class, MessageReference.class)
                    .invoke(null, fixture.message, fixture.reference));
            verify(fixture.message, never()).copy();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final URLClassLoader loader = PatchJarLoader.open();
        final Class<?> type = Class.forName(
                "org.apache.activemq.artemis.protocol.amqp.proton.ServiceBusMessageMetadataSupport", true, loader);
        final AMQPMessage message = mock(AMQPMessage.class);
        final MessageReference reference = mock(MessageReference.class, RETURNS_DEEP_STUBS);

        Fixture(String kind) throws Exception {
            when(reference.getQueue().getUser()).thenReturn(SimpleString.of("floci-az:servicebus-" + kind + ":60"));
            when(reference.getMessageID()).thenReturn(42L);
            when(message.getIngressTimestamp()).thenReturn(ENQUEUED_TIME);
        }

        MessageAnnotations annotations(MessageAnnotations original) throws Exception {
            return (MessageAnnotations) type.getMethod("annotationsForDelivery",
                    MessageAnnotations.class, AMQPMessage.class, MessageReference.class)
                    .invoke(null, original, message, reference);
        }

        Header header(Header original) throws Exception {
            return (Header) type.getMethod("headerForDelivery", Header.class, MessageReference.class)
                    .invoke(null, original, reference);
        }

        @Override
        public void close() throws Exception {
            loader.close();
        }
    }
}
