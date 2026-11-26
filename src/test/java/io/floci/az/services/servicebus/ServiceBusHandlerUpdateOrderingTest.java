package io.floci.az.services.servicebus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(15)
class ServiceBusHandlerUpdateOrderingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final ServiceBusEntityXml.DuplicateDetectionSettings NO_DUPLICATES =
            new ServiceBusEntityXml.DuplicateDetectionSettings(false, 0);
    private static final ServiceBusEntityXml.MessageLifetimeSettings LIFETIME =
            new ServiceBusEntityXml.MessageLifetimeSettings(300_000, false);
    private final Map<String, StoredObject> stored = new ConcurrentHashMap<>();
    private StorageBackend<String, StoredObject> store;
    private ServiceBusNamespaceManager namespaceManager;
    private ServiceBusHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void createHandler() {
        store = mock(StorageBackend.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        namespaceManager = mock(ServiceBusNamespaceManager.class);
        when(namespaceManager.getMessageCounts(anyString(), anyString()))
                .thenReturn(ServiceBusNamespaceManager.MessageCounts.ZERO);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(serviceBus.maxDeliveryCount()).thenReturn(10);
        when(serviceBus.lockDurationSeconds()).thenReturn(60L);
        when(storageFactory.create("servicebus")).thenReturn(store);
        when(store.get(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        doAnswer(call -> {
            stored.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(store).put(anyString(), any());
        doAnswer(call -> {
            stored.remove(call.getArgument(0));
            return null;
        }).when(store).delete(anyString());
        when(store.scan(any())).thenAnswer(call -> {
            Predicate<String> filter = call.getArgument(0);
            return stored.values().stream().filter(object -> filter.test(object.key())).toList();
        });
        handler = new ServiceBusHandler(config, namespaceManager, storageFactory);
    }

    @Test
    void concurrentQueueUpdatesKeepStoredAndBrokerSettingsInOrder() throws Exception {
        String key = "sb/account/demo/queues/work";
        put(key, ServiceBusModels.QueueEntity.defaults("work", 10, 60, false, NO_DUPLICATES, LIFETIME));
        CountDownLatch applyingFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong brokerLockSeconds = new AtomicLong();
        doAnswer(call -> {
            long lockSeconds = call.getArgument(3);
            if (lockSeconds == 30) {
                applyingFirst.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
            }
            brokerLockSeconds.set(lockSeconds);
            return null;
        }).when(namespaceManager).jolokiaUpdateQueue(
                anyString(), anyString(), anyBoolean(), anyLong(), anyInt(), any(), any());

        runOverlappingUpdates("queues/work", "<LockDuration>PT30S</LockDuration>",
                "queues/work", "<LockDuration>PT50S</LockDuration>", applyingFirst, releaseFirst);

        ServiceBusModels.QueueEntity queue = MAPPER.readValue(stored.get(key).data(), ServiceBusModels.QueueEntity.class);
        assertEquals(50, queue.lockDurationSeconds());
        assertEquals(queue.lockDurationSeconds(), brokerLockSeconds.get());
    }

    @Test
    void subscriptionUpdateWaitsForRelatedTopicUpdate() throws Exception {
        put("sb/account/demo/topics/orders", ServiceBusModels.TopicEntity.defaults("orders", NO_DUPLICATES, LIFETIME));
        String key = "sb/account/demo/topics/orders/subscriptions/audit";
        put(key, ServiceBusModels.SubscriptionEntity.defaults("orders", "audit", 10, 60, false, LIFETIME));
        CountDownLatch applyingFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong brokerLockSeconds = new AtomicLong();
        AtomicLong brokerTtlMillis = new AtomicLong();
        doAnswer(call -> {
            applyingFirst.countDown();
            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
            return null;
        }).when(namespaceManager).jolokiaUpdateTopic(anyString(), anyString(), any());
        doAnswer(call -> {
            brokerLockSeconds.set(call.getArgument(4));
            ServiceBusEntityXml.MessageLifetimeSettings lifetime = call.getArgument(7);
            brokerTtlMillis.set(lifetime.ttlMillis());
            return null;
        }).when(namespaceManager).jolokiaUpdateSubscription(
                anyString(), anyString(), anyString(), anyBoolean(), anyLong(), anyInt(), any(), any());

        runOverlappingUpdates("topics/orders", "<DefaultMessageTimeToLive>PT2S</DefaultMessageTimeToLive>",
                "topics/orders/subscriptions/audit", "<LockDuration>PT50S</LockDuration>", applyingFirst, releaseFirst);

        ServiceBusModels.SubscriptionEntity subscription = MAPPER.readValue(
                stored.get(key).data(), ServiceBusModels.SubscriptionEntity.class);
        assertEquals(50, subscription.lockDurationSeconds());
        assertEquals(subscription.lockDurationSeconds(), brokerLockSeconds.get());
        assertEquals(2_000, brokerTtlMillis.get());
    }

    @Test
    void deletingASubscriptionWaitsForItsUpdateExistenceCheck() throws Exception {
        put("sb/account/demo/topics/orders", ServiceBusModels.TopicEntity.defaults("orders", NO_DUPLICATES, LIFETIME));
        String key = "sb/account/demo/topics/orders/subscriptions/audit";
        put(key, ServiceBusModels.SubscriptionEntity.defaults("orders", "audit", 10, 60, false, LIFETIME));
        CountDownLatch readingSubscription = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        when(store.get(anyString())).thenAnswer(call -> {
            String requestedKey = call.getArgument(0);
            Optional<StoredObject> snapshot = Optional.ofNullable(stored.get(requestedKey));
            // Block the update's existence check, which runs under the handler's lock; the name resolution in routing
            // reads the same key before taking it
            if (key.equals(requestedKey) && Thread.holdsLock(handler) && firstRead.compareAndSet(true, false)) {
                readingSubscription.countDown();
                assertTrue(releaseRead.await(5, TimeUnit.SECONDS));
            }
            return snapshot;
        });

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Response> update = executor.submit(() ->
                    update("topics/orders/subscriptions/audit", "<LockDuration>PT50S</LockDuration>"));
            try {
                assertTrue(readingSubscription.await(5, TimeUnit.SECONDS));
                CountDownLatch deleteStarted = new CountDownLatch(1);
                Future<Response> delete = executor.submit(() -> {
                    deleteStarted.countDown();
                    return handler.handle(new AzureRequest("DELETE", "account", "servicebus",
                            "demo/topics/orders/subscriptions/audit", null, null, Map.of(), null, false));
                });
                assertTrue(deleteStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> delete.get(200, TimeUnit.MILLISECONDS),
                        "Deletion must wait for the update's subscription lookup and broker change");
                releaseRead.countDown();
                assertEquals(200, update.get(5, TimeUnit.SECONDS).getStatus());
                assertEquals(200, delete.get(5, TimeUnit.SECONDS).getStatus());
                assertFalse(stored.containsKey(key));
            } finally {
                releaseRead.countDown();
            }
        }
    }

    @Test
    void updatingAMissingSubscriptionAnswersNotFoundWithoutCreatingIt() throws Exception {
        put("sb/account/demo/topics/orders", ServiceBusModels.TopicEntity.defaults("orders", NO_DUPLICATES, LIFETIME));

        Response response = update("topics/orders/subscriptions/audit",
                "<RequiresSession>true</RequiresSession><LockDuration>PT50S</LockDuration>");

        assertEquals(404, response.getStatus());
        assertFalse(stored.containsKey("sb/account/demo/topics/orders/subscriptions/audit"));
        assertFalse(stored.containsKey("sb/account/demo/topics/orders/subscriptions/audit/rules/$Default"));
    }

    private void runOverlappingUpdates(String firstPath, String firstBody, String secondPath, String secondBody,
                                       CountDownLatch applyingFirst, CountDownLatch releaseFirst) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Response> first = executor.submit(() -> update(firstPath, firstBody));
            try {
                assertTrue(applyingFirst.await(5, TimeUnit.SECONDS));
                CountDownLatch secondStarted = new CountDownLatch(1);
                Future<Response> second = executor.submit(() -> {
                    secondStarted.countDown();
                    return update(secondPath, secondBody);
                });
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS),
                        "A related update must wait until the first broker update finishes");
                releaseFirst.countDown();
                assertEquals(200, first.get(5, TimeUnit.SECONDS).getStatus());
                assertEquals(200, second.get(5, TimeUnit.SECONDS).getStatus());
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    private Response update(String path, String settings) {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getHeaderString("If-Match")).thenReturn("*");
        String description;
        if (path.contains("/subscriptions/")) {
            description = "SubscriptionDescription";
        } else if (path.startsWith("queues/")) {
            description = "QueueDescription";
        } else {
            description = "TopicDescription";
        }
        String body = "<" + description + " xmlns=\"http://schemas.microsoft.com/netservices/2010/10/servicebus/connect\">"
                + settings + "</" + description + ">";
        return handler.handle(new AzureRequest("PUT", "account", "servicebus", "demo/" + path,
                headers, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), Map.of(), null, false));
    }

    private void put(String key, Object entity) throws Exception {
        stored.put(key, new StoredObject(key, MAPPER.writeValueAsBytes(entity), Map.of(), Instant.now(), key));
    }
}
