package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.qpid.proton.engine.Sender;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;

class ServiceBusSessionTimerTest {
    @Test
    void releasingSessionCancelsItsExpiryTask() throws Exception {
        try (var loader = PatchJarLoader.open()) {
            var type = loader.loadClass("org.apache.activemq.artemis.protocol.amqp.proton.ServiceBusSessionSupport");
            var server = mock(ActiveMQServer.class);
            var scheduler = mock(ScheduledExecutorService.class);
            var timer = mock(ScheduledFuture.class);
            var sender = mock(Sender.class);
            when(server.getScheduledPool()).thenReturn(scheduler);
            doReturn(timer).when(scheduler).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
            var reserve = Arrays.stream(type.getDeclaredMethods()).filter(method -> method.getName().equals("reserve")).findFirst().orElseThrow();
            reserve.setAccessible(true);
            reserve.invoke(null, server, mock(Queue.class), SimpleString.of("timer-test"), "session", sender, null, 30000L);
            type.getMethod("release", Sender.class).invoke(null, sender);
            verify(timer).cancel(false);
        }
    }
    @Test
    void expiredOwnerClosesItsLinkOnlyOnce() throws Exception {
        try (var loader = PatchJarLoader.open()) {
            var type = loader.loadClass("org.apache.activemq.artemis.protocol.amqp.proton.ServiceBusSessionSupport");
            var server = mock(ActiveMQServer.class);
            var scheduler = mock(ScheduledExecutorService.class);
            var timer = mock(ScheduledFuture.class);
            var sender = mock(Sender.class);
            var queue = mock(Queue.class);
            doReturn(timer).when(scheduler).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
            var context = mock(ProtonServerSenderContext.class);
            when(server.getScheduledPool()).thenReturn(scheduler);
            var reserve = Arrays.stream(type.getDeclaredMethods()).filter(method -> method.getName().equals("reserve")).findFirst().orElseThrow();
            reserve.setAccessible(true);
            Object owner = reserve.invoke(null, server, queue, SimpleString.of("expiry-test"), "session", sender, context, 30000L);
            var expire = Arrays.stream(type.getDeclaredMethods()).filter(method -> method.getName().equals("expire")).findFirst().orElseThrow();
            expire.setAccessible(true);
            var keyType = Arrays.stream(type.getDeclaredClasses()).filter(nested -> nested.getSimpleName().equals("SessionKey")).findFirst().orElseThrow();
            var keyConstructor = keyType.getDeclaredConstructors()[0];
            keyConstructor.setAccessible(true);
            Object key = keyConstructor.newInstance(SimpleString.of("expiry-test"), "session");
            expire.invoke(null, key, owner);
            expire.invoke(null, key, owner);
            verify(context).close(argThat((org.apache.qpid.proton.amqp.transport.ErrorCondition condition) ->
                    "com.microsoft:session-lock-lost".equals(condition.getCondition().toString())));
            verify(queue).resetGroup(SimpleString.of("session"));
        }
    }

}
