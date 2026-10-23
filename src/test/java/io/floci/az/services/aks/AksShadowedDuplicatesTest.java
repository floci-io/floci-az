package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AKS: duplicates the old casing bug left behind are dropped with their containers")
class AksShadowedDuplicatesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static ManagedCluster cache(String resourceGroup, String instanceId) {
        ManagedCluster cache = new ManagedCluster();
        cache.setInstanceId(instanceId);
        cache.setSubscriptionId("sub-dup");
        cache.setResourceGroup(resourceGroup);
        cache.setName("cluster1");
        cache.setLocation("eastus");
        cache.setProvisioningState("Succeeded");
        cache.setCreatedAt(Instant.now());
        return cache;
    }

    private static void put(InMemoryStorage<String, StoredObject> backend, String key, ManagedCluster cache,
                            Instant written) throws Exception {
        backend.put(key, new StoredObject(key, MAPPER.writeValueAsBytes(cache), Map.of(), written, "etag"));
    }

    @Test
    void theOlderCasingIsDroppedWithItsContainerAndTheNewerStays() throws Exception {
        InMemoryStorage<String, StoredObject> backend = new InMemoryStorage<>();
        put(backend, "sub-dup/RG/cluster1", cache("RG", "older001"), Instant.now().minusSeconds(60));
        put(backend, "sub-dup/rg/cluster1", cache("rg", "newer001"), Instant.now());
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create("aks")).thenReturn(backend);
        AksClusterManager containers = mock(AksClusterManager.class);
        AksHandler handler = new AksHandler(mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), containers, factory);

        handler.dropShadowedClusters();

        verify(containers).stopCluster(argThat(cache -> "older001".equals(cache.getInstanceId())));
        verify(containers, never()).stopCluster(argThat(cache -> "newer001".equals(cache.getInstanceId())));
        assertFalse(backend.get("sub-dup/RG/cluster1").isPresent(), "the shadowed duplicate is dropped");
        assertTrue(backend.get("sub-dup/rg/cluster1").isPresent(), "the served copy stays");
    }
}
