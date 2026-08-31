package io.floci.az.services.servicebus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceBusHandlerRuleConsistencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @SuppressWarnings("unchecked")
    void failedBrokerUpdateRestoresDeletedRule() throws Exception {
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
        when(store.scan(any())).thenReturn(List.of());
        doThrow(new IllegalStateException("Jolokia unavailable"))
                .when(namespaceManager)
                .jolokiaUpdateSubscriptionFilter(
                        anyString(), anyString(), anyString(), anyString());
        ServiceBusHandler handler = new ServiceBusHandler(
                mock(EmulatorConfig.class), namespaceManager, storageFactory);

        Response response = handler.handleDeleteRule(
                "account", "namespace", "topic", "subscription", "existing");

        assertEquals(500, response.getStatus());
        verify(store).put(key, stored);
    }
}
