package io.floci.az.services;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.core.tls.SidecarCertificates;
import io.floci.az.services.acr.AcrRegistryManager;
import io.floci.az.services.redis.RedisCacheManager;
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
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * With floci-az in a container, Docker's default bridge does not resolve container names, so
 * floci-az's own connections to Redis and the shared ACR registry (readiness and the registry
 * proxy) must use the container IP there. On a user-defined network they keep the container name.
 * The host name Redis reports to clients does not change either way.
 */
@DisplayName("Sidecars on the default bridge are reached by container IP")
class SidecarDefaultBridgeAddressingTest {

    private static final String SIDECAR_IP = "172.17.0.5";
    private static final int HOST_PORT = 6390;

    private ContainerBuilder.Builder builder;
    private ContainerBuilder containerBuilder;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerDetector containerDetector;
    private PortAllocator portAllocator;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        containerBuilder = mock(ContainerBuilder.class);
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(any())).thenReturn(builder);

        lifecycleManager = mock(ContainerLifecycleManager.class);
        containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        portAllocator = mock(PortAllocator.class);
        when(portAllocator.allocate(anyInt(), anyInt())).thenReturn(HOST_PORT);

        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().redis().maxMemory()).thenReturn("64mb");
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
    }

    @Test
    void redisOnTheDefaultBridgeIsProbedByContainerIp() {
        RedisCache cache = startRedis("bridge");

        assertEquals(SIDECAR_IP + ":6379", cache.getInternalEndpoint());
        assertEquals("floci-az-redis-abc123", cache.getHostName());
        assertEquals(6379, cache.getPort());
    }

    @Test
    void redisWithNoNetworkIsOnTheDefaultBridgeAndProbedByContainerIp() {
        RedisCache cache = startRedis(null);

        assertEquals(SIDECAR_IP + ":6379", cache.getInternalEndpoint());
        assertEquals("floci-az-redis-abc123", cache.getHostName());
    }

    @Test
    void redisOnAUserDefinedNetworkKeepsItsContainerName() {
        RedisCache cache = startRedis("app-net");

        assertEquals("floci-az-redis-abc123:6379", cache.getInternalEndpoint());
        assertEquals("floci-az-redis-abc123", cache.getHostName());
    }

    @Test
    void acrOnTheDefaultBridgeIsProxiedByContainerIp() {
        assertEquals(SIDECAR_IP + ":5000", startAcr("bridge").dataPlaneEndpoint());
    }

    @Test
    void acrOnAUserDefinedNetworkKeepsItsContainerName() {
        assertEquals("floci-az-acr-registry:5000", startAcr("app-net").dataPlaneEndpoint());
    }

    private RedisCache startRedis(String network) {
        when(builder.build()).thenReturn(spec("floci-az-redis-abc123", network));
        when(lifecycleManager.createAndStart(any())).thenReturn(startedAt(6379));
        RedisCache cache = new RedisCache();
        cache.setName("cache");
        cache.setInstanceId("abc123");
        cache.setPrimaryKey("primary-key");
        new RedisCacheManager(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                certificates(), config).startCache(cache);
        return cache;
    }

    private static SidecarCertificates certificates() {
        return mock(SidecarCertificates.class);
    }

    private AcrRegistryManager startAcr(String network) {
        when(builder.build()).thenReturn(spec("floci-az-acr-registry", network));
        when(lifecycleManager.createAndStart(any())).thenReturn(startedAt(5000));
        AcrRegistryManager manager =
                new AcrRegistryManager(containerBuilder, lifecycleManager, containerDetector, portAllocator, config);
        manager.ensureStarted();
        return manager;
    }

    private static ContainerSpec spec(String name, String network) {
        return new ContainerSpec("image:test", name, List.of(), null, null, null, Map.of(), List.of(), network,
                List.of(), List.of(), List.of(), Map.of(), null, false, null, List.of(), null, null, List.of());
    }

    /** What startCreated resolves in container mode: the container IP and the container port. */
    private static ContainerLifecycleManager.ContainerInfo startedAt(int containerPort) {
        return new ContainerLifecycleManager.ContainerInfo("sidecar-id",
                Map.of(containerPort, new ContainerLifecycleManager.EndpointInfo(SIDECAR_IP, containerPort)));
    }
}
