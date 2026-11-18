package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.utils.collections.TypedProperties;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.Properties;
import org.apache.qpid.proton.message.Message;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLClassLoader;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The broker re-encodes a message after giving it its routing address (the ingress timestamp does
 * it for every Service Bus message). Stock Artemis writes that address into {@code properties.to}
 * then, so a receiver saw the entity name instead of the sender's To.
 */
class AmqpStandardMessageReencodeTest {

    private static final String STANDARD_MESSAGE =
            "org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage";

    @Test
    void reencodingKeepsTheToTheSenderSet() throws Exception {
        assertEquals("billing", reencodedTo("billing"));
    }

    @Test
    void reencodingDoesNotFillInAToTheSenderLeftOut() throws Exception {
        assertNull(reencodedTo(null));
    }

    private static String reencodedTo(String to) throws Exception {
        Message sent = Proton.message();
        sent.setAddress(to);
        sent.setReplyTo("replies");
        sent.setBody(new AmqpValue("hello"));
        byte[] buffer = new byte[4096];
        int size = sent.encode(buffer, 0, buffer.length);

        try (URLClassLoader loader = new PatchedBrokerLoader(PatchJarLoader.open())) {
            Class<?> type = Class.forName(STANDARD_MESSAGE, true, loader);
            assertSame(loader, type.getClassLoader(), "the patched class must be under test, not the stock one");
            Object message = type.getConstructor(long.class, byte[].class, TypedProperties.class)
                    .newInstance(0L, Arrays.copyOf(buffer, size), null);
            type.getMethod("setAddress", SimpleString.class).invoke(message, SimpleString.of("orders"));
            type.getMethod("reencode").invoke(message);
            Properties properties = (Properties) type.getMethod("getProperties").invoke(message);
            assertEquals("replies", properties.getReplyTo());
            assertEquals("orders", type.getMethod("getAddress").invoke(message));
            return properties.getTo();
        }
    }

    /**
     * Loads the Artemis AMQP broker classes from the patched jar ahead of the stock copies on the
     * test classpath, which {@link PatchJarLoader}'s parent-first loader would pick.
     */
    private static final class PatchedBrokerLoader extends URLClassLoader {
        private static final String BROKER_PACKAGE = "org.apache.activemq.artemis.protocol.amqp.broker.";

        private final URLClassLoader patchJar;

        PatchedBrokerLoader(URLClassLoader patchJar) {
            super(patchJar.getURLs(), patchJar.getParent());
            this.patchJar = patchJar;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(BROKER_PACKAGE)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                patchJar.close();
            }
        }
    }
}
