package io.floci.az.services.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.redis.RedisModels.RedisCache;
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

@DisplayName("Redis: duplicates the old casing bug left behind are dropped with their containers")
class RedisShadowedDuplicatesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static RedisCache cache(String resourceGroup, String instanceId) {
        RedisCache cache = new RedisCache();
        cache.setInstanceId(instanceId);
        cache.setSubscriptionId("sub-dup");
        cache.setResourceGroup(resourceGroup);
        cache.setName("cache1");
        cache.setLocation("eastus");
        cache.setProvisioningState("Succeeded");
        cache.setCreatedAt(Instant.now());
        return cache;
    }

    private static void put(InMemoryStorage<String, StoredObject> backend, String key, RedisCache cache,
                            Instant written) throws Exception {
        backend.put(key, new StoredObject(key, MAPPER.writeValueAsBytes(cache), Map.of(), written, "etag"));
    }

    @Test
    void theOlderCasingIsDroppedWithItsContainerAndTheNewerStays() throws Exception {
        InMemoryStorage<String, StoredObject> backend = new InMemoryStorage<>();
        put(backend, "sub-dup/RG/cache1", cache("RG", "older001"), Instant.now().minusSeconds(60));
        put(backend, "sub-dup/rg/cache1", cache("rg", "newer001"), Instant.now());
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create("redis")).thenReturn(backend);
        RedisCacheManager containers = mock(RedisCacheManager.class);
        RedisHandler handler = new RedisHandler(mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), containers, factory,
            mock(GlobalNameRegistry.class), CloudDefaults.catalogOnly());

        handler.dropShadowedCaches();

        verify(containers).stopCache(argThat(cache -> "older001".equals(cache.getInstanceId())));
        verify(containers, never()).stopCache(argThat(cache -> "newer001".equals(cache.getInstanceId())));
        assertFalse(backend.get("sub-dup/RG/cache1").isPresent(), "the shadowed duplicate is dropped");
        assertTrue(backend.get("sub-dup/rg/cache1").isPresent(), "the served copy stays");
    }
}
