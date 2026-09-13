package org.apache.activemq.artemis.protocol.amqp.proton;

import io.netty.buffer.ByteBuf;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPLargeMessage;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPMessage;
import org.apache.activemq.artemis.protocol.amqp.util.TLSEncode;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.UnsignedInteger;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.amqp.messaging.Header;
import org.apache.qpid.proton.amqp.messaging.MessageAnnotations;
import org.apache.qpid.proton.amqp.messaging.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLClassLoader;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceBusLargeMessageSupportTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copiesCurrentMetadataAndOnlyRemovesExpiryForDeadLetter(boolean deadLetter) throws Exception {
        AMQPLargeMessage source = mock(AMQPLargeMessage.class, CALLS_REAL_METHODS);
        Header header = new Header();
        header.setTtl(UnsignedInteger.valueOf(30_000));
        Properties properties = new Properties();
        properties.setAbsoluteExpiryTime(new Date(60_000));
        properties.setGroupId("session");
        setField(source, "header", header);
        setField(source, "properties", properties);
        setField(source, "messageAnnotations", new MessageAnnotations(Map.of(
                Symbol.valueOf("x-opt-ingress-time"), 42L)));
        setField(source, "applicationProperties", new ApplicationProperties(Map.of(
                "DeadLetterReason", "poison", "custom", "kept")));
        setField(source, "remainingBodyPosition", 123);
        AtomicInteger bodyPosition = new AtomicInteger();
        var previousBuffer = TLSEncode.getEncoder().getBuffer();

        try (URLClassLoader loader = PatchJarLoader.open()) {
            Class<?> support = Class.forName(
                    "org.apache.activemq.artemis.protocol.amqp.broker.ServiceBusLargeMessageSupport", true, loader);
            ByteBuf buffer = (ByteBuf) support.getMethod("headerForCopy",
                    AMQPLargeMessage.class, AtomicInteger.class, boolean.class)
                    .invoke(null, source, bodyPosition, deadLetter);
            try {
                byte[] encoded = new byte[buffer.readableBytes()];
                buffer.readBytes(encoded);
                var copied = Proton.message();
                copied.decode(encoded, 0, encoded.length);
                assertEquals(deadLetter ? null : header.getTtl(), copied.getHeader().getTtl());
                assertEquals(deadLetter ? null : properties.getAbsoluteExpiryTime(), copied.getProperties().getAbsoluteExpiryTime());
                assertEquals("session", copied.getProperties().getGroupId());
                assertEquals(42L, copied.getMessageAnnotations().getValue().get(Symbol.valueOf("x-opt-ingress-time")));
                assertEquals(Map.of("DeadLetterReason", "poison", "custom", "kept"), copied.getApplicationProperties().getValue());
                assertNull(copied.getBody());
                assertEquals(123, bodyPosition.get());
                assertSame(previousBuffer, TLSEncode.getEncoder().getBuffer());
            } finally {
                buffer.release();
            }
        }
        assertEquals(UnsignedInteger.valueOf(30_000), header.getTtl());
        assertEquals(new Date(60_000), properties.getAbsoluteExpiryTime());
        verify(source, never()).getBody();
        verify(source, never()).getMessageAnnotations();
    }

    private static void setField(AMQPLargeMessage message, String name, Object value) throws Exception {
        var field = AMQPMessage.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(message, value);
    }
}
