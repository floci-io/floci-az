package io.floci.az.artemis;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.postoffice.DuplicateIDCache;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.RoutingContext;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Maps AMQP message-id to Artemis duplicate detection for configured Service Bus entities.
 *
 * <p>Artemis provides the atomic, transactional duplicate check. This plugin limits each
 * address's cache entries to the Azure duplicate-detection history window.
 */
public final class ServiceBusDuplicateDetectionPlugin
        implements ActiveMQServerPlugin, ServiceBusDuplicateDetectionPluginMBean {

    public static final String OBJECT_NAME =
            "io.floci.az.artemis:type=ServiceBusDuplicateDetection";

    private final ConcurrentHashMap<String, Long> windowsByAddress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<DuplicateKey, Long> expiresByKey = new ConcurrentHashMap<>();

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
            throw new IllegalStateException("Could not register duplicate detection MBean", e);
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
        } catch (Exception ignored) {
            // Broker shutdown must continue even if JMX has already stopped.
        } finally {
            windowsByAddress.clear();
            expiresByKey.clear();
            server = null;
        }
    }

    @Override
    public void configure(String address, long historyWindowMillis) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("address is required");
        }
        if (historyWindowMillis <= 0) {
            throw new IllegalArgumentException("historyWindowMillis must be positive");
        }
        windowsByAddress.put(address, historyWindowMillis);
    }

    @Override
    public void remove(String address) {
        windowsByAddress.remove(address);
        expiresByKey.keySet().removeIf(key -> key.address().equals(address));
        ActiveMQServer activeServer = server;
        if (activeServer != null) {
            try {
                activeServer.getPostOffice().getDuplicateIDCache(SimpleString.of(address)).clear();
            } catch (Exception ignored) {
                // Removing an entity is best effort during broker teardown.
            }
        }
    }

    @Override
    public void beforeMessageRoute(
            Message message, RoutingContext context, boolean direct, boolean rejectDuplicates) {
        String address = context.getAddress(message).toString();
        Long historyWindowMillis = windowsByAddress.get(address);
        Object messageId = message.getUserID();
        if (historyWindowMillis == null || messageId == null) {
            return;
        }

        byte[] duplicateId = messageId.toString().getBytes(StandardCharsets.UTF_8);
        DuplicateKey key = new DuplicateKey(address, messageId.toString());
        long now = System.currentTimeMillis();
        expiresByKey.computeIfPresent(key, (ignored, existingExpiry) -> {
            if (existingExpiry <= now) {
                evict(address, duplicateId);
                return null;
            }
            return existingExpiry;
        });

        ActiveMQServer activeServer = Objects.requireNonNull(server, "plugin is not registered");
        DuplicateIDCache cache =
                activeServer.getPostOffice().getDuplicateIDCache(SimpleString.of(address));
        final boolean accepted;
        try {
            accepted = cache.atomicVerify(duplicateId, context.getTransaction());
        } catch (Exception e) {
            throw new IllegalStateException("Could not check duplicate id for " + address, e);
        }
        if (!accepted) {
            context.clear();
            return;
        }

        expiresByKey.computeIfAbsent(key, ignored -> {
            long newExpiry = Math.addExact(now, historyWindowMillis);
            scheduleEviction(key, duplicateId, newExpiry, historyWindowMillis);
            return newExpiry;
        });
    }

    private void scheduleEviction(DuplicateKey key, byte[] duplicateId,
                                  long expiry, long delayMillis) {
        ActiveMQServer activeServer = Objects.requireNonNull(server, "plugin is not registered");
        activeServer.getScheduledPool().schedule(
                () -> expiresByKey.computeIfPresent(key, (ignored, currentExpiry) -> {
                    if (currentExpiry == expiry) {
                        evict(key.address(), duplicateId);
                        return null;
                    }
                    return currentExpiry;
                }),
                delayMillis,
                TimeUnit.MILLISECONDS);
    }

    private void evict(String address, byte[] duplicateId) {
        ActiveMQServer activeServer = server;
        if (activeServer == null) {
            return;
        }
        try {
            DuplicateIDCache cache = activeServer.getPostOffice()
                    .getDuplicateIDCache(SimpleString.of(address));
            cache.deleteFromCache(duplicateId);
        } catch (Exception e) {
            throw new IllegalStateException("Could not expire duplicate id for " + address, e);
        }
    }

    private record DuplicateKey(String address, String messageId) {}
}
