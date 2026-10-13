package io.floci.az.services;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.core.tls.SidecarCertificates;
import io.floci.az.services.acr.AcrRegistryManager;
import io.floci.az.services.aks.AksClusterManager;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import io.floci.az.services.redis.RedisCacheManager;
import io.floci.az.services.redis.RedisModels.RedisCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis, AKS and ACR take their sidecar's host ports from a fixed range. The port must go back to
 * the pool when the start fails and when the container is removed, or a long-running emulator
 * drains the range (21 ports for Redis) and every later create fails with "No free port".
 */
@DisplayName("Range-allocated sidecars release their host port")
class SidecarPortReleaseTest {

    private static final int HOST_PORT = 6390;
    private static final int TLS_HOST_PORT = 6391;

    private ContainerBuilder containerBuilder;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerDetector containerDetector;
    private PortAllocator portAllocator;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(new ContainerSpec("image:test"));
        when(containerBuilder.newContainer(any())).thenReturn(builder);

        lifecycleManager = mock(ContainerLifecycleManager.class);
        containerDetector = mock(ContainerDetector.class);
        portAllocator = mock(PortAllocator.class);
        when(portAllocator.allocate(anyInt(), anyInt())).thenReturn(HOST_PORT);

        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().redis().maxMemory()).thenReturn("64mb");
    }

    @Test
    void redisReleasesItsPortWhenTheStartFails() {
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("port is already allocated"));

        assertThrows(RuntimeException.class, () -> redis().startCache(cache()));

        verify(portAllocator).release(HOST_PORT);
        verify(portAllocator, times(1)).allocate(anyInt(), anyInt());
    }

    @Test
    void redisReleasesItsPortWhenTheCacheIsStopped() {
        when(lifecycleManager.createAndStart(any())).thenReturn(running("redis-container"));
        RedisCacheManager manager = redis();
        RedisCache cache = cache();

        manager.startCache(cache);
        verify(portAllocator, never()).release(anyInt());
        manager.stopCache(cache);

        verify(portAllocator).release(HOST_PORT);
        verify(portAllocator, times(1)).release(anyInt());
    }

    @Test
    void redisWithTlsReleasesBothPortsWhenTheStartFails() {
        redisTlsPorts();
        when(lifecycleManager.create(any())).thenReturn("redis-container");
        when(lifecycleManager.startCreated(any(), any())).thenThrow(new RuntimeException("port is already allocated"));

        assertThrows(RuntimeException.class, () -> redis().startCache(cache()));

        verify(lifecycleManager).removeIfExists("redis-container");
        verify(portAllocator).release(HOST_PORT);
        verify(portAllocator).release(TLS_HOST_PORT);
    }

    @Test
    void redisWithTlsReleasesThePlainPortWhenNoTlsPortIsFree() {
        when(config.services().redis().tlsEnabled()).thenReturn(true);
        when(portAllocator.allocate(anyInt(), anyInt()))
                .thenReturn(HOST_PORT)
                .thenThrow(new IllegalStateException("No free port in range 6379-6399"));

        assertThrows(IllegalStateException.class, () -> redis().startCache(cache()));

        verify(lifecycleManager, never()).create(any());
        verify(portAllocator).release(HOST_PORT);
    }

    @Test
    void redisWithTlsReleasesBothPortsWhenTheCacheIsStopped() {
        redisTlsPorts();
        when(lifecycleManager.create(any())).thenReturn("redis-container");
        when(lifecycleManager.startCreated(any(), any())).thenReturn(running("redis-container"));
        RedisCacheManager manager = redis();
        RedisCache cache = cache();

        manager.startCache(cache);
        verify(portAllocator, never()).release(anyInt());
        manager.stopCache(cache);

        verify(portAllocator).release(HOST_PORT);
        verify(portAllocator).release(TLS_HOST_PORT);
    }

    @Test
    void aksReleasesItsPortWhenTheStartFails() {
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("port is already allocated"));

        assertThrows(RuntimeException.class, () -> aks().startCluster(cluster()));

        verify(portAllocator).release(HOST_PORT);
    }

    @Test
    void aksReleasesItsPortWhenTheVolumeSetupFails() {
        RuntimeException volumeFailure = new RuntimeException("volume create timed out");
        doThrow(volumeFailure).when(lifecycleManager).ensureVolume(anyString());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> aks().startCluster(cluster()));

        assertSame(volumeFailure, thrown);
        verify(lifecycleManager, never()).createAndStart(any());
        verify(portAllocator).release(HOST_PORT);
    }

    @Test
    void aksReleasesItsPortWhenTheFailedStartCleanupAlsoFails() {
        RuntimeException startFailure = new RuntimeException("port is already allocated");
        when(lifecycleManager.createAndStart(any())).thenThrow(startFailure);
        when(lifecycleManager.volumeExists(anyString())).thenReturn(false);
        doThrow(new RuntimeException("daemon gone")).when(lifecycleManager).removeVolume(anyString());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> aks().startCluster(cluster()));

        assertSame(startFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        verify(portAllocator).release(HOST_PORT);
    }

    @Test
    void aksReleasesItsPortWhenTheClusterIsStopped() {
        when(lifecycleManager.createAndStart(any())).thenReturn(running("k3s-container"));
        when(config.services().aks().keepRunningOnShutdown()).thenReturn(false);
        AksClusterManager manager = aks();
        ManagedCluster cluster = cluster();

        manager.startCluster(cluster);
        manager.stopCluster(cluster);

        verify(portAllocator).release(HOST_PORT);
    }

    @Test
    void aksKeepsItsPortWhenTheContainerIsLeftRunning() {
        when(lifecycleManager.createAndStart(any())).thenReturn(running("k3s-container"));
        when(config.services().aks().keepRunningOnShutdown()).thenReturn(true);
        AksClusterManager manager = aks();
        ManagedCluster cluster = cluster();

        manager.startCluster(cluster);
        manager.stopCluster(cluster);

        verify(portAllocator, never()).release(anyInt());
    }

    @Test
    void acrReleasesItsPortWhenTheStartFails() {
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("port is already allocated"));

        assertThrows(RuntimeException.class, () -> acr().ensureStarted());

        verify(portAllocator).release(HOST_PORT);
    }

    @Test
    void acrReleasesThePreviousPortBeforeRestartingADeadRegistry() {
        when(lifecycleManager.createAndStart(any())).thenReturn(running("registry-container"));
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(false);
        AcrRegistryManager manager = acr();

        manager.ensureStarted();
        verify(portAllocator, never()).release(anyInt());
        manager.ensureStarted();

        verify(portAllocator).release(HOST_PORT);
    }

    private RedisCacheManager redis() {
        SidecarCertificates certificates = mock(SidecarCertificates.class);
        when(certificates.issue(any())).thenReturn(new SidecarCertificates.ServerCertificate("chain", "key", "ca"));
        return new RedisCacheManager(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                certificates, config);
    }

    /** With TLS enabled, a Redis cache takes two ports from the range: plain, then TLS. */
    private void redisTlsPorts() {
        when(config.services().redis().tlsEnabled()).thenReturn(true);
        when(portAllocator.allocate(anyInt(), anyInt())).thenReturn(HOST_PORT, TLS_HOST_PORT);
    }

    private AksClusterManager aks() {
        return new AksClusterManager(containerBuilder, lifecycleManager, containerDetector, portAllocator, config);
    }

    private AcrRegistryManager acr() {
        return new AcrRegistryManager(containerBuilder, lifecycleManager, containerDetector, portAllocator, config);
    }

    private static ContainerLifecycleManager.ContainerInfo running(String containerId) {
        return new ContainerLifecycleManager.ContainerInfo(containerId, Map.of());
    }

    private static RedisCache cache() {
        RedisCache cache = new RedisCache();
        cache.setName("cache");
        cache.setInstanceId("abc123");
        cache.setPrimaryKey("primary-key");
        return cache;
    }

    private static ManagedCluster cluster() {
        return new ManagedCluster();
    }
}
