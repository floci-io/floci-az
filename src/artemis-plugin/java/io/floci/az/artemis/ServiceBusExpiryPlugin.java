package io.floci.az.artemis;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.postoffice.RoutingStatus;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.RoutingContext;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.core.server.impl.AckReason;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;
import org.apache.activemq.artemis.core.transaction.Transaction;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Applies Azure Service Bus default TTL independently to every routed queue.
 *
 * <p>Artemis address settings cannot model subscription-specific TTL because every
 * subscription queue shares its topic address. Scheduling expiration per routed queue
 * preserves that distinction while using Artemis's queue expiry and move operations.
 */
public final class ServiceBusExpiryPlugin
        implements ActiveMQServerPlugin, ServiceBusExpiryPluginMBean {

    private static final System.Logger LOG =
            System.getLogger(ServiceBusExpiryPlugin.class.getName());
    public static final String OBJECT_NAME = "io.floci.az.artemis:type=ServiceBusExpiry";

    private final ConcurrentHashMap<String, ExpirySettings> settingsByQueue =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ExpiryKey, ExpiryDeadline> expiryDeadlines =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ExpiryKey, ScheduledFuture<?>> scheduledExpirations =
            new ConcurrentHashMap<>();
    private volatile ActiveMQServer server;
    private volatile ObjectName objectName;

    @Override
    public void registered(ActiveMQServer registeredServer) {
        server = registeredServer;
        try {
            objectName = new ObjectName(OBJECT_NAME);
            MBeanServer mBeanServer = registeredServer.getMBeanServer();
            if (mBeanServer == null) {
                mBeanServer = ManagementFactory.getPlatformMBeanServer();
            }
            if (mBeanServer.isRegistered(objectName)) {
                mBeanServer.unregisterMBean(objectName);
            }
            mBeanServer.registerMBean(this, objectName);
        } catch (Exception e) {
            throw new IllegalStateException("Could not register message expiry MBean", e);
        }
    }

    @Override
    public void unregistered(ActiveMQServer unregisteredServer) {
        try {
            MBeanServer mBeanServer = unregisteredServer.getMBeanServer();
            if (mBeanServer == null) {
                mBeanServer = ManagementFactory.getPlatformMBeanServer();
            }
            if (objectName != null && mBeanServer.isRegistered(objectName)) {
                mBeanServer.unregisterMBean(objectName);
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Could not unregister message expiry MBean during broker shutdown", e);
        } finally {
            scheduledExpirations.values().forEach(task -> task.cancel(false));
            scheduledExpirations.clear();
            expiryDeadlines.clear();
            settingsByQueue.clear();
            server = null;
        }
    }

    @Override
    public void configure(String queueName, long defaultTtlMillis, String deadLetterAddress) {
        if (queueName == null || queueName.isBlank()) {
            throw new IllegalArgumentException("queueName is required");
        }
        if (defaultTtlMillis <= 0) {
            throw new IllegalArgumentException("defaultTtlMillis must be positive");
        }
        settingsByQueue.put(queueName, new ExpirySettings(
                defaultTtlMillis,
                deadLetterAddress == null || deadLetterAddress.isBlank()
                        ? null : deadLetterAddress));
    }

    @Override
    public void remove(String queueName) {
        settingsByQueue.remove(queueName);
        scheduledExpirations.forEach((key, task) -> {
            if (key.queueName().equals(queueName)
                    && scheduledExpirations.remove(key, task)) {
                task.cancel(false);
            }
        });
        expiryDeadlines.keySet().removeIf(key -> key.queueName().equals(queueName));
    }

    @Override
    public void afterMessageRoute(
            Message message,
            RoutingContext context,
            boolean direct,
            boolean rejectDuplicates,
            RoutingStatus result) {
        if (result != RoutingStatus.OK) {
            return;
        }

        Set<Queue> queues = new HashSet<>(context.getDurableQueues(context.getAddress()));
        queues.addAll(context.getNonDurableQueues(context.getAddress()));
        ActiveMQServer activeServer = server;
        if (activeServer == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Queue queue : queues) {
            ExpirySettings settings = settingsByQueue.get(queue.getName().toString());
            if (settings == null) {
                continue;
            }
            long delayMillis = effectiveDelay(
                    message.getExpiration(), now, settings.defaultTtlMillis());
            long messageId = message.getMessageID();
            scheduleExpiry(activeServer, queue, messageId, settings, now, delayMillis);
        }
    }

    @Override
    public boolean canAccept(ServerConsumer consumer, MessageReference reference) {
        ExpiryKey key = expiryKey(reference);
        ExpiryDeadline deadline = expiryDeadlines.get(key);
        if (deadline == null || deadline.expiresAtMillis() > System.currentTimeMillis()) {
            return true;
        }
        if (expire(reference.getQueue(), reference.getMessageID(), deadline.deadLetterAddress())) {
            clearExpiry(key);
        }
        return false;
    }

    @Override
    public void messageAcknowledged(MessageReference reference, AckReason reason) {
        cancelExpiry(reference);
    }

    @Override
    public void messageExpired(
            MessageReference reference, SimpleString expiryAddress, ServerConsumer consumer) {
        cancelExpiry(reference);
    }

    @Override
    public void messageMoved(
            Transaction transaction,
            MessageReference reference,
            AckReason reason,
            SimpleString destinationAddress,
            Long destinationQueueId,
            ServerConsumer consumer,
            Message newMessage,
            RoutingStatus result) {
        cancelExpiry(reference);
    }

    private static long effectiveDelay(long messageExpiration, long now, long defaultTtlMillis) {
        if (messageExpiration <= 0) {
            return defaultTtlMillis;
        }
        return Math.min(defaultTtlMillis, Math.max(0, messageExpiration - now));
    }

    private void scheduleExpiry(
            ActiveMQServer activeServer,
            Queue queue,
            long messageId,
            ExpirySettings settings,
            long now,
            long delayMillis) {
        ExpiryKey key = new ExpiryKey(queue.getName().toString(), messageId);
        synchronized (scheduledExpirations) {
            if (expiryDeadlines.putIfAbsent(key, new ExpiryDeadline(
                    saturatingAdd(now, delayMillis), settings.deadLetterAddress())) != null) {
                return;
            }
            ScheduledFuture<?> task = activeServer.getScheduledPool().schedule(() -> {
                synchronized (scheduledExpirations) {
                    scheduledExpirations.remove(key);
                    if (isDelivering(queue, messageId)) {
                        return;
                    }
                    if (expire(queue, messageId, settings.deadLetterAddress())) {
                        expiryDeadlines.remove(key);
                    }
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
            scheduledExpirations.put(key, task);
        }
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private void cancelExpiry(MessageReference reference) {
        clearExpiry(expiryKey(reference));
    }

    private void clearExpiry(ExpiryKey key) {
        expiryDeadlines.remove(key);
        ScheduledFuture<?> task = scheduledExpirations.remove(key);
        if (task != null) {
            task.cancel(false);
        }
    }

    private static ExpiryKey expiryKey(MessageReference reference) {
        return new ExpiryKey(
                reference.getQueue().getName().toString(), reference.getMessageID());
    }

    private static boolean isDelivering(Queue queue, long messageId) {
        return queue.getDeliveringMessages().values().stream()
                .flatMap(Collection::stream)
                .anyMatch(reference -> reference.getMessageID() == messageId);
    }

    private static boolean expire(Queue queue, long messageId, String deadLetterAddress) {
        try {
            if (deadLetterAddress == null) {
                return queue.expireReference(messageId);
            }
            return queue.moveReference(
                    messageId, SimpleString.of(deadLetterAddress), null, false);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not expire message " + messageId + " from " + queue.getName(), e);
        }
    }

    private record ExpirySettings(long defaultTtlMillis, String deadLetterAddress) {}

    private record ExpiryDeadline(long expiresAtMillis, String deadLetterAddress) {}

    private record ExpiryKey(String queueName, long messageId) {}
}
