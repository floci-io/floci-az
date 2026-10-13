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
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Azure reports a cache's SSL port and azurerm builds its connection strings on it
 * ({@code host:sslPort,password=...,ssl=True}), so the port reported as {@code sslPort} must serve
 * TLS: with {@code floci-az.services.redis.tls-enabled}, the container listens on 6380 with the
 * issued certificate, published like the plain port.
 */
@DisplayName("Redis caches with TLS enabled serve TLS on the reported SSL port")
class RedisCacheManagerTlsTest {

    private static final int HOST_PORT = 6390;
    private static final int TLS_HOST_PORT = 6391;
    private static final String CONTAINER_NAME = "floci-az-redis-abc123";
    private static final SidecarCertificates.ServerCertificate ISSUED =
            new SidecarCertificates.ServerCertificate("chain-pem", "key-pem", "ca-pem");

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
        when(lifecycleManager.create(any())).thenReturn("redis-container");
        when(lifecycleManager.startCreated(any(), any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                "redis-container",
                Map.of(6379, new ContainerLifecycleManager.EndpointInfo("localhost", HOST_PORT),
                        6380, new ContainerLifecycleManager.EndpointInfo("localhost", TLS_HOST_PORT))));
        containerDetector = mock(ContainerDetector.class);
        portAllocator = mock(PortAllocator.class);
        when(portAllocator.allocate(anyInt(), anyInt())).thenReturn(HOST_PORT, TLS_HOST_PORT);
        certificates = mock(SidecarCertificates.class);
        when(certificates.issue(any())).thenReturn(ISSUED);

        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().redis().maxMemory()).thenReturn("64mb");
        when(config.services().redis().tlsEnabled()).thenReturn(true);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
    }

    @Test
    void theCommandLineServesTlsOn6380WithTheCopiedCertificateFiles() {
        RedisCache cache = cache();

        List<String> cmd = RedisCacheManager.command(cache, config.services().redis());

        assertEquals(List.of(
                "valkey-server",
                "--requirepass", "primary-key",
                "--maxmemory", "64mb",
                "--maxmemory-policy", "allkeys-lru",
                "--tls-port", "6380",
                "--tls-cert-file", "/tls/server.crt",
                "--tls-key-file", "/tls/server.key",
                "--tls-ca-cert-file", "/tls/ca.crt",
                "--tls-auth-clients", "no"), cmd);
    }

    @Test
    void onTheHostTheSslPortIsTheHostPortPublishedForTls() {
        RedisCache cache = start();

        assertEquals("localhost", cache.getHostName());
        assertEquals(HOST_PORT, cache.getPort());
        assertEquals(TLS_HOST_PORT, cache.getSslPort());
        verify(builder).withPortBinding(6379, HOST_PORT);
        verify(builder).withPortBinding(6380, TLS_HOST_PORT);
        verify(certificates).issue(List.of(CONTAINER_NAME));
    }

    @Test
    void inAContainerTheSslPortIsTheContainerPortLikeThePlainPort() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        RedisCache cache = start();

        assertEquals(CONTAINER_NAME, cache.getHostName());
        assertEquals(6379, cache.getPort());
        assertEquals(6380, cache.getSslPort());
    }

    @Test
    void inPublishedModeTheSslPortIsTheTlsHostPortAndTheCertificateNamesTheDaemon() {
        when(lifecycleManager.publishedEndpoints()).thenReturn(true);
        when(lifecycleManager.daemonAddress()).thenReturn("10.0.0.7");

        RedisCache cache = start();

        assertEquals("10.0.0.7", cache.getHostName());
        assertEquals(HOST_PORT, cache.getPort());
        assertEquals(TLS_HOST_PORT, cache.getSslPort());
        verify(certificates).issue(List.of(CONTAINER_NAME, "10.0.0.7"));
    }

    @Test
    void theCertificateFilesAreInPlaceBeforeTheContainerStarts() {
        start();

        InOrder order = inOrder(lifecycleManager);
        order.verify(lifecycleManager).create(any());
        order.verify(lifecycleManager).copyFileToContainer("redis-container", "chain-pem", "/tls/server.crt");
        order.verify(lifecycleManager).copyFileToContainer("redis-container", "key-pem", "/tls/server.key");
        order.verify(lifecycleManager).copyFileToContainer("redis-container", "ca-pem", "/tls/ca.crt");
        order.verify(lifecycleManager).startCreated(eq("redis-container"), any());
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
