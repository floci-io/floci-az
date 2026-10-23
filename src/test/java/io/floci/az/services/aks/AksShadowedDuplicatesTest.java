package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
        cache.setContainerId("cid-" + instanceId);
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

        verify(containers).removeCluster(argThat(cache -> "older001".equals(cache.getInstanceId())));
        verify(containers, never()).removeCluster(argThat(cache -> "newer001".equals(cache.getInstanceId())));
        assertFalse(backend.get("sub-dup/RG/cluster1").isPresent(), "the shadowed duplicate is dropped");
        assertTrue(backend.get("sub-dup/rg/cluster1").isPresent(), "the served copy stays");
    }

    @Test
    void theDuplicateContainerIsRemovedEvenWhenClustersAreKeptRunningOnShutdown() throws Exception {
        InMemoryStorage<String, StoredObject> backend = new InMemoryStorage<>();
        put(backend, "sub-dup/RG/cluster1", cache("RG", "older001"), Instant.now().minusSeconds(60));
        put(backend, "sub-dup/rg/cluster1", cache("rg", "newer001"), Instant.now());
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create("aks")).thenReturn(backend);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().aks().mocked()).thenReturn(false);
        when(config.services().aks().keepRunningOnShutdown()).thenReturn(true);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        AksClusterManager containers = new AksClusterManager(mock(ContainerBuilder.class), lifecycle,
            mock(ContainerDetector.class), mock(PortAllocator.class), config);
        AksHandler handler = new AksHandler(config, containers, factory);

        handler.dropShadowedClusters();

        verify(lifecycle).stopAndRemove(eq("cid-older001"), any());
        verify(lifecycle).removeVolume("floci-az-aks-older001");
        verify(lifecycle, never()).stopAndRemove(eq("cid-newer001"), any());
        assertFalse(backend.get("sub-dup/RG/cluster1").isPresent(), "the shadowed duplicate is dropped");
    }

    @Test
    void shutdownStillLeavesTheContainerRunningWhenConfiguredTo() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().aks().keepRunningOnShutdown()).thenReturn(true);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        AksClusterManager containers = new AksClusterManager(mock(ContainerBuilder.class), lifecycle,
            mock(ContainerDetector.class), mock(PortAllocator.class), config);

        containers.stopCluster(cache("rg", "kept0001"));

        verify(lifecycle, never()).stopAndRemove(any(), any());
        verify(lifecycle, never()).removeVolume(any());
    }
}
