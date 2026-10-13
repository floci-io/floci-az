package io.floci.az.services.redis;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.core.tls.SidecarCertificates;
import io.floci.az.services.redis.RedisModels.RedisCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TLS for caches is opt-in. With {@code floci-az.services.redis.tls-enabled} at its default, a cache
 * takes one host port and its container starts exactly as before TLS existed: plain RESP only, no
 * certificate, and {@code sslPort} reported as Azure's 6380.
 */
@DisplayName("Redis caches without TLS enabled keep one port and the plain command line")
class RedisCacheManagerDefaultTest {

    private static final int HOST_PORT = 6390;
    private static final String CONTAINER_NAME = "floci-az-redis-abc123";

    private ContainerBuilder.Builder builder;
    private ContainerBuilder containerBuilder;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerDetector containerDetector;
    private PortAllocator portAllocator;
    private SidecarCertificates certificates;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        containerBuilder = mock(ContainerBuilder.class);
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(new ContainerSpec("image:test", CONTAINER_NAME, List.of(), null, null,
                null, Map.of(), List.of(), "app-net", List.of(), List.of(), List.of(), Map.of(), null, false,
                null, List.of(), null, null, List.of()));
        when(containerBuilder.newContainer(any())).thenReturn(builder);

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                "redis-container",
                Map.of(6379, new ContainerLifecycleManager.EndpointInfo("localhost", HOST_PORT))));
        containerDetector = mock(ContainerDetector.class);
        portAllocator = mock(PortAllocator.class);
        when(portAllocator.allocate(anyInt(), anyInt())).thenReturn(HOST_PORT);
        certificates = mock(SidecarCertificates.class);

        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().redis().maxMemory()).thenReturn("64mb");
        when(config.services().redis().tlsEnabled()).thenReturn(false);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
    }

    @Test
    void theCommandLineServesPlainRespOnly() {
        List<String> cmd = RedisCacheManager.command(cache(), config.services().redis());

        assertEquals(List.of(
                "valkey-server",
                "--requirepass", "primary-key",
                "--maxmemory", "64mb",
                "--maxmemory-policy", "allkeys-lru"), cmd);
    }

    @Test
    void oneHostPortIsAllocatedAndPublished() {
        RedisCache cache = start();

        verify(portAllocator, times(1)).allocate(anyInt(), anyInt());
        verify(builder).withPortBinding(6379, HOST_PORT);
        verify(builder, never()).withPortBinding(eq(6380), anyInt());
        assertEquals("localhost", cache.getHostName());
        assertEquals(HOST_PORT, cache.getPort());
        assertEquals(6380, cache.getSslPort());
    }

    @Test
    void theContainerStartsInOneStepWithNoCertificate() {
        start();

        verify(lifecycleManager).createAndStart(any());
        verify(lifecycleManager, never()).create(any());
        verify(lifecycleManager, never()).copyFileToContainer(anyString(), anyString(), anyString());
        verifyNoInteractions(certificates);
    }

    @Test
    void inAContainerTheSslPortStaysAzuresDefault() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        RedisCache cache = start();

        assertEquals(CONTAINER_NAME, cache.getHostName());
        assertEquals(6379, cache.getPort());
        assertEquals(6380, cache.getSslPort());
    }

    private RedisCache start() {
        RedisCache cache = cache();
        new RedisCacheManager(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                certificates, config).startCache(cache);
        return cache;
    }

    private static RedisCache cache() {
        RedisCache cache = new RedisCache();
        cache.setName("cache");
        cache.setInstanceId("abc123");
        cache.setPrimaryKey("primary-key");
        cache.setSslPort(6380);
        return cache;
    }
}
