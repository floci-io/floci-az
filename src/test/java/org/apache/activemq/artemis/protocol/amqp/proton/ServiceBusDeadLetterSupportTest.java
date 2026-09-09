package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ServiceBusDeadLetterSupportTest {

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
