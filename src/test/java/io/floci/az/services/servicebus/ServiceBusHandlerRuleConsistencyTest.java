package io.floci.az.services.servicebus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceBusHandlerRuleConsistencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @SuppressWarnings("unchecked")
    void failedBrokerUpdateDoesNotDeleteStoredRule() throws Exception {
        String key = "sb/account/namespace/topics/topic/subscriptions/subscription/rules/existing";
        ServiceBusModels.RuleEntity rule = ServiceBusModels.RuleEntity.trueFilter(
                "topic", "subscription", "existing");
        StoredObject stored = new StoredObject(
                key, MAPPER.writeValueAsBytes(rule), Map.of(), Instant.now(), key);
        StorageBackend<String, StoredObject> store = mock(StorageBackend.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        ServiceBusNamespaceManager namespaceManager = mock(ServiceBusNamespaceManager.class);
        when(storageFactory.create("servicebus")).thenReturn(store);
        when(store.get(key)).thenReturn(Optional.of(stored));
        when(store.scan(any())).thenReturn(List.of(stored));
        doThrow(new IllegalStateException("Jolokia unavailable"))
                .when(namespaceManager)
                .jolokiaUpdateSubscriptionFilter(
                        anyString(), anyString(), anyString(), anyString());
        ServiceBusHandler handler = new ServiceBusHandler(
                mock(EmulatorConfig.class), namespaceManager, storageFactory);

        Response response = handler.handleDeleteRule(
                "account", "namespace", "topic", "subscription", "existing");

        assertEquals(500, response.getStatus());
        verify(store, never()).delete(key);
        verify(store, never()).put(anyString(), any());
        verify(store, never()).applyBatch(any(), any());
        verify(namespaceManager).jolokiaUpdateSubscriptionFilter(
                "namespace", "topic", "subscription", ServiceBusRuleSelector.MATCH_NONE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void replacementCommitsStorageAfterBrokerUpdate() throws Exception {
        String oldKey = "sb/account/namespace/topics/topic/subscriptions/subscription/rules/old";
        String newKey = "sb/account/namespace/topics/topic/subscriptions/subscription/rules/new";
        ServiceBusModels.RuleEntity oldRule = ServiceBusModels.RuleEntity.trueFilter(
                "topic", "subscription", "old");
        ServiceBusModels.RuleEntity newRule = ServiceBusModels.RuleEntity.trueFilter(
                "topic", "subscription", "new");
        StoredObject stored = new StoredObject(
                oldKey, MAPPER.writeValueAsBytes(oldRule), Map.of(), Instant.now(), oldKey);
        StorageBackend<String, StoredObject> store = mock(StorageBackend.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        ServiceBusNamespaceManager namespaceManager = mock(ServiceBusNamespaceManager.class);
        when(storageFactory.create("servicebus")).thenReturn(store);
        when(store.scan(any())).thenReturn(List.of(stored));
        ServiceBusHandler handler = new ServiceBusHandler(
                mock(EmulatorConfig.class), namespaceManager, storageFactory);

        Response response = handler.replaceRules(
                "account", "namespace", "topic", "subscription", List.of(newRule));

        assertEquals(200, response.getStatus());
        InOrder mutation = inOrder(namespaceManager, store);
        mutation.verify(namespaceManager).jolokiaUpdateSubscriptionFilter(
                "namespace", "topic", "subscription", ServiceBusRuleSelector.MATCH_ALL);
        mutation.verify(store).applyBatch(
                argThat(puts -> puts.keySet().equals(Set.of(newKey))), eq(Set.of(oldKey)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedStorageBatchRetriesPreviousBrokerFilter() throws Exception {
        String oldKey = "sb/account/namespace/topics/topic/subscriptions/subscription/rules/old";
        ServiceBusModels.RuleEntity oldRule = new ServiceBusModels.RuleEntity(
                "topic", "subscription", "old", "FalseFilter",
                null, null, null, null, null, null, null, null, null,
                Map.of(), Map.of(), null, Instant.EPOCH);
        ServiceBusModels.RuleEntity newRule = ServiceBusModels.RuleEntity.trueFilter(
                "topic", "subscription", "new");
        StoredObject stored = new StoredObject(
                oldKey, MAPPER.writeValueAsBytes(oldRule), Map.of(), Instant.now(), oldKey);
        StorageBackend<String, StoredObject> store = mock(StorageBackend.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        ServiceBusNamespaceManager namespaceManager = mock(ServiceBusNamespaceManager.class);
        when(storageFactory.create("servicebus")).thenReturn(store);
        when(store.scan(any())).thenReturn(List.of(stored));
        doThrow(new IllegalStateException("storage unavailable"))
                .when(store).applyBatch(any(), any());
        doNothing()
                .doThrow(new IllegalStateException("first rollback failed"))
                .doNothing()
                .when(namespaceManager).jolokiaUpdateSubscriptionFilter(
                        anyString(), anyString(), anyString(), anyString());
        ServiceBusHandler handler = new ServiceBusHandler(
                mock(EmulatorConfig.class), namespaceManager, storageFactory);

        Response response = handler.replaceRules(
                "account", "namespace", "topic", "subscription", List.of(newRule));

        assertEquals(500, response.getStatus());
        InOrder mutation = inOrder(namespaceManager, store);
        mutation.verify(namespaceManager).jolokiaUpdateSubscriptionFilter(
                "namespace", "topic", "subscription", ServiceBusRuleSelector.MATCH_ALL);
        mutation.verify(store).applyBatch(any(), any());
        mutation.verify(namespaceManager, times(2)).jolokiaUpdateSubscriptionFilter(
                "namespace", "topic", "subscription", ServiceBusRuleSelector.MATCH_NONE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exhaustedBrokerRollbackRestartsNamespaceWithPersistedTopology() throws Exception {
        String queueKey = "sb/account/namespace/queues/queue";
        String topicKey = "sb/account/namespace/topics/topic";
        String subscriptionKey = "sb/account/namespace/topics/topic/subscriptions/subscription";
        String oldKey = "sb/account/namespace/topics/topic/subscriptions/subscription/rules/old";
        ServiceBusModels.QueueEntity queue = new ServiceBusModels.QueueEntity(
                "queue", 10, 60, 1024, false, false, 600,
                86_400_000, false, Instant.EPOCH, Instant.EPOCH);
        ServiceBusModels.TopicEntity topic = new ServiceBusModels.TopicEntity(
                "topic", 1024, false, 600, 86_400_000, Instant.EPOCH, Instant.EPOCH);
        ServiceBusModels.SubscriptionEntity subscription = new ServiceBusModels.SubscriptionEntity(
                "topic", "subscription", 10, 60, false, 86_400_000,
                false, Instant.EPOCH, Instant.EPOCH);
        ServiceBusModels.RuleEntity oldRule = new ServiceBusModels.RuleEntity(
                "topic", "subscription", "old", "FalseFilter",
                null, null, null, null, null, null, null, null, null,
                Map.of(), Map.of(), null, Instant.EPOCH);
        ServiceBusModels.RuleEntity newRule = ServiceBusModels.RuleEntity.trueFilter(
                "topic", "subscription", "new");
        List<StoredObject> storedObjects = List.of(
                new StoredObject(queueKey, MAPPER.writeValueAsBytes(queue),
                        Map.of(), Instant.now(), queueKey),
                new StoredObject(topicKey, MAPPER.writeValueAsBytes(topic),
                        Map.of(), Instant.now(), topicKey),
                new StoredObject(subscriptionKey, MAPPER.writeValueAsBytes(subscription),
                        Map.of(), Instant.now(), subscriptionKey),
                new StoredObject(oldKey, MAPPER.writeValueAsBytes(oldRule),
                        Map.of(), Instant.now(), oldKey));
        StorageBackend<String, StoredObject> store = mock(StorageBackend.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        ServiceBusNamespaceManager namespaceManager = mock(ServiceBusNamespaceManager.class);
        when(storageFactory.create("servicebus")).thenReturn(store);
        when(store.scan(any())).thenAnswer(invocation -> {
            Predicate<String> predicate = invocation.getArgument(0);
            return storedObjects.stream().filter(obj -> predicate.test(obj.key())).toList();
        });
        doThrow(new IllegalStateException("storage unavailable"))
                .when(store).applyBatch(any(), any());
        doNothing()
                .doThrow(new IllegalStateException("rollback failed"))
                .doThrow(new IllegalStateException("rollback failed"))
                .doThrow(new IllegalStateException("rollback failed"))
                .when(namespaceManager).jolokiaUpdateSubscriptionFilter(
                        anyString(), anyString(), anyString(), anyString());
        ServiceBusNamespaceManager.NamespaceState namespaceState =
                new ServiceBusNamespaceManager.NamespaceState(
                        "container", 5672, 5671, "certificate", "localhost", 8161, false);
        when(namespaceManager.getNamespace("namespace")).thenReturn(Optional.of(namespaceState));
        when(namespaceManager.stopNamespace("namespace")).thenReturn(true);
        when(namespaceManager.startNamespace("namespace", 5672, 5671)).thenReturn(namespaceState);
        ServiceBusHandler handler = new ServiceBusHandler(
                mock(EmulatorConfig.class), namespaceManager, storageFactory);

        Response response = handler.replaceRules(
                "account", "namespace", "topic", "subscription", List.of(newRule));

        assertEquals(500, response.getStatus());
        verify(namespaceManager, times(4)).jolokiaUpdateSubscriptionFilter(
                anyString(), anyString(), anyString(), anyString());
        verify(namespaceManager).stopNamespace("namespace");
        verify(namespaceManager).startNamespace("namespace", 5672, 5671);
        verify(namespaceManager).jolokiaCreateQueue(
                eq("namespace"), eq("queue"), eq(false), eq(60L), eq(10), any(), any());
        verify(namespaceManager).jolokiaCreateTopic(eq("namespace"), eq("topic"), any());
        verify(namespaceManager).jolokiaCreateSubscription(
                eq("namespace"), eq("topic"), eq("subscription"),
                eq(ServiceBusRuleSelector.MATCH_NONE), eq(false), eq(60L), eq(10), any(), any());
    }
}
