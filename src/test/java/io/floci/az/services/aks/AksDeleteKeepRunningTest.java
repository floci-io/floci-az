package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.StoredObject;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AKS: keep-running-on-shutdown covers shutdown only, never an explicit delete")
class AksDeleteKeepRunningTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String KEY = "sub-keep/rg/cluster1";

    private InMemoryStorage<String, StoredObject> backend;
    private ContainerLifecycleManager lifecycle;
    private AksHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        ManagedCluster cluster = new ManagedCluster();
        cluster.setInstanceId("kept0001");
        cluster.setContainerId("cid-kept0001");
        cluster.setSubscriptionId("sub-keep");
        cluster.setResourceGroup("rg");
        cluster.setName("cluster1");
        cluster.setLocation("eastus");
        cluster.setProvisioningState("Succeeded");
        cluster.setCreatedAt(Instant.now());
        backend = new InMemoryStorage<>();
        backend.put(KEY, new StoredObject(KEY, MAPPER.writeValueAsBytes(cluster), Map.of(), Instant.now(), "etag"));
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create("aks")).thenReturn(backend);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().aks().mocked()).thenReturn(false);
        when(config.services().aks().keepRunningOnShutdown()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4577");
        lifecycle = mock(ContainerLifecycleManager.class);
        AksClusterManager containers = new AksClusterManager(mock(ContainerBuilder.class), lifecycle,
            mock(ContainerDetector.class), mock(PortAllocator.class), config);
        handler = new AksHandler(config, containers, factory);
    }

    @Test
    void deletingAClusterRemovesItsContainerAndVolumeEvenWhenClustersAreKeptRunning() {
        AzureRequest delete = new AzureRequest("DELETE", "arm", "aks",
            "subscriptions/sub-keep/resourceGroups/rg/providers/Microsoft.ContainerService/managedClusters/cluster1",
            null, null, Map.of("api-version", "2024-05-01"), null, false);

        Response response = handler.handle(delete);

        assertThat(response.getStatus(), is(202));
        verify(lifecycle).stopAndRemove(eq("cid-kept0001"), any());
        verify(lifecycle).removeVolume("floci-az-aks-kept0001");
        assertFalse(backend.get(KEY).isPresent(), "the deleted cluster's record is gone");
    }

    @Test
    void shuttingDownStillLeavesTheContainerAndVolumeWhenClustersAreKeptRunning() {
        handler.shutdown();

        verify(lifecycle, never()).stopAndRemove(any(), any());
        verify(lifecycle, never()).removeVolume(any());
    }
}
