package org.apache.activemq.artemis.protocol.amqp.proton;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServiceBusLockTokenTest {
    @Test
    void pooledBufferGetsANewLockToken() throws Exception {
        try (var loader = PatchJarLoader.open()) {
            // A platform parent prevents the stock Artemis type from shadowing the patched class.
            try (var isolated = new java.net.URLClassLoader(loader.getURLs(), ClassLoader.getPlatformClassLoader())) {
                var type = isolated.loadClass("org.apache.activemq.artemis.protocol.amqp.proton.AmqpTransferTagGenerator");
                var generator = type.getConstructor().newInstance();
                byte[] buffer = (byte[]) type.getMethod("getNextTag").invoke(generator);
                byte[] previous = buffer.clone();
                type.getMethod("returnTag", byte[].class).invoke(generator, (Object) buffer);
                byte[] next = (byte[]) type.getMethod("getNextTag").invoke(generator);
                assertSame(buffer, next);
                assertFalse(java.util.Arrays.equals(previous, next));
                assertEquals(16, next.length);
                var secondGenerator = type.getConstructor().newInstance();
                byte[] other = (byte[]) type.getMethod("getNextTag").invoke(secondGenerator);
                assertFalse(java.util.Arrays.equals(next, other));
            }
        }
    }
}
