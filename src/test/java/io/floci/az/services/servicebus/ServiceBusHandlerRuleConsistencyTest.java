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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
}
