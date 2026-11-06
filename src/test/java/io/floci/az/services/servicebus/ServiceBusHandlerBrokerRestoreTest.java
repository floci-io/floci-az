package io.floci.az.services.servicebus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceBusHandlerBrokerRestoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final ServiceBusEntityXml.DuplicateDetectionSettings NO_DUPLICATE_DETECTION =
            new ServiceBusEntityXml.DuplicateDetectionSettings(false, 0);
    private static final ServiceBusEntityXml.MessageLifetimeSettings DEFAULT_LIFETIME =
            new ServiceBusEntityXml.MessageLifetimeSettings(
                    ServiceBusEntityXml.DEFAULT_MESSAGE_TTL_MILLIS, false);

    private final Map<String, StoredObject> stored = new LinkedHashMap<>();
    private ServiceBusNamespaceManager namespaceManager;
    private ServiceBusHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void createHandler() {
        StorageBackend<String, StoredObject> store = mock(StorageBackend.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        namespaceManager = mock(ServiceBusNamespaceManager.class);
        when(storageFactory.create("servicebus")).thenReturn(store);
        when(store.get(anyString())).thenAnswer(call ->
                Optional.ofNullable(stored.get(call.<String>getArgument(0))));
        when(store.scan(any())).thenAnswer(call -> {
            Predicate<String> filter = call.getArgument(0);
            return stored.values().stream().filter(object -> filter.test(object.key())).toList();
        });
        handler = new ServiceBusHandler(mock(EmulatorConfig.class), namespaceManager, storageFactory);
    }

    @Test
    void recreatesStoredTopicBeforeItsSubscriptionWithTheStoredRuleFilter() throws Exception {
        // Stored subscription first: the broker still needs the topic created before it.
        ServiceBusModels.SubscriptionEntity subscription = subscription("orders", "audit");
        put("sb/account/demo/topics/orders/subscriptions/audit", subscription);
        ServiceBusModels.RuleEntity rule = new ServiceBusModels.RuleEntity(
                "orders", "audit", "only-created", "SqlFilter", "kind = 'created'",
                null, null, null, null, null, null, null, null,
                Map.of(), Map.of(), null, Instant.now());
        put("sb/account/demo/topics/orders/subscriptions/audit/rules/only-created", rule);
        put("sb/account/demo/topics/orders", topic("orders"));

        handler.onBrokerStarted(new ServiceBusBrokerStarted("demo"));

        InOrder order = inOrder(namespaceManager);
        order.verify(namespaceManager).jolokiaCreateTopic(eq("demo"), eq("orders"), any());
        order.verify(namespaceManager).jolokiaCreateSubscription(
                eq("demo"), eq("orders"), eq("audit"), eq(ServiceBusRuleSelector.forRules(List.of(rule))),
                eq(subscription.requiresSession()), eq(subscription.lockDurationSeconds()),
                eq(subscription.maxDeliveryCount()), any(), any());
    }

    @Test
    void recreatesStoredQueues() throws Exception {
        ServiceBusModels.QueueEntity queue = queue("work");
        put("sb/account/demo/queues/work", queue);

        handler.onBrokerStarted(new ServiceBusBrokerStarted("demo"));

        verify(namespaceManager).jolokiaCreateQueue(
                eq("demo"), eq("work"), eq(queue.requiresSession()), eq(queue.lockDurationSeconds()),
                eq(queue.maxDeliveryCount()), any(), any());
    }

    @Test
    void leavesOtherNamespacesAlone() throws Exception {
        put("sb/account/other/queues/work", queue("work"));
        put("sb/account/other/topics/orders", topic("orders"));

        handler.onBrokerStarted(new ServiceBusBrokerStarted("demo"));

        verify(namespaceManager, never()).jolokiaCreateQueue(
                anyString(), anyString(), anyBoolean(), anyLong(), anyInt(), any(), any());
        verify(namespaceManager, never()).jolokiaCreateTopic(anyString(), anyString(), any());
    }

    @Test
    void oneEntityFailingDoesNotStopTheRest() throws Exception {
        put("sb/account/demo/queues/broken", queue("broken"));
        put("sb/account/demo/queues/working", queue("working"));
        doThrow(new IllegalStateException("Jolokia unavailable"))
                .when(namespaceManager).jolokiaCreateQueue(
                        eq("demo"), eq("broken"), anyBoolean(), anyLong(), anyInt(), any(), any());

        handler.onBrokerStarted(new ServiceBusBrokerStarted("demo"));

        verify(namespaceManager).jolokiaCreateQueue(
                eq("demo"), eq("working"), anyBoolean(), anyLong(), anyInt(), any(), any());
    }

    private void put(String key, Object entity) throws Exception {
        stored.put(key, new StoredObject(
                key, MAPPER.writeValueAsBytes(entity), Map.of(), Instant.now(), key));
    }

    private static ServiceBusModels.QueueEntity queue(String name) {
        return ServiceBusModels.QueueEntity.defaults(
                name, 10, 60, false, NO_DUPLICATE_DETECTION, DEFAULT_LIFETIME);
    }

    private static ServiceBusModels.TopicEntity topic(String name) {
        return ServiceBusModels.TopicEntity.defaults(name, NO_DUPLICATE_DETECTION, DEFAULT_LIFETIME);
    }

    private static ServiceBusModels.SubscriptionEntity subscription(String topicName, String name) {
        return ServiceBusModels.SubscriptionEntity.defaults(
                topicName, name, 10, 60, false, DEFAULT_LIFETIME);
    }
}
