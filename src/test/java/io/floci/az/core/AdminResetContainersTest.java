package io.floci.az.core;

import io.floci.az.services.acr.AcrRegistryManager;
import io.floci.az.services.aks.AksClusterManager;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import io.floci.az.services.redis.RedisCacheManager;
import io.floci.az.services.redis.RedisModels.RedisCache;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@code POST /_admin/reset} drops every record, so it must also remove the containers behind them: nothing else
 * would, and a test suite resetting between tests left one container per cache behind on every run.
 */
@QuarkusTest
@TestProfile(AdminResetContainersTest.ContainerBacked.class)
@DisplayName("/_admin/reset removes the sidecar containers of the resources it drops")
class AdminResetContainersTest {

    private static final String RG = "/subscriptions/sub-reset/resourceGroups/rg-reset/providers";

    /** Container-backed mode, with the container managers mocked so no container really starts. */
    public static class ContainerBacked implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.services.redis.mocked", "false",
                    "floci-az.services.aks.mocked", "false",
                    "floci-az.services.acr.mocked", "false");
        }
    }

    @InjectMock
    RedisCacheManager cacheManager;

    @InjectMock
    AksClusterManager clusterManager;

    @InjectMock
    AcrRegistryManager registryManager;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        clearInvocations(cacheManager, clusterManager, registryManager);
    }

    @Test
    void aResetStopsTheContainerOfEveryRedisCache() {
        for (String name : List.of("resetone", "resettwo")) {
            given().contentType("application/json")
                    .body("{\"location\":\"eastus\",\"properties\":{\"sku\":"
                            + "{\"name\":\"Basic\",\"family\":\"C\",\"capacity\":0}}}")
                    .when().put(RG + "/Microsoft.Cache/Redis/" + name + "?api-version=2024-11-01")
                    .then().statusCode(201);
        }

        given().post("/_admin/reset").then().statusCode(204);

        ArgumentCaptor<RedisCache> stopped = ArgumentCaptor.forClass(RedisCache.class);
        verify(cacheManager, times(2)).stopCache(stopped.capture());
        assertThat(stopped.getAllValues().stream().map(RedisCache::getName).toList(),
                containsInAnyOrder("resetone", "resettwo"));
    }

    @Test
    void aResetRemovesTheK3sContainerOfEveryAksCluster() {
        for (String name : List.of("resetone", "resettwo")) {
            given().contentType("application/json")
                    .body("""
                        {"location": "eastus", "properties": {"dnsPrefix": "%s",
                          "agentPoolProfiles": [{"name": "nodepool1", "count": 1, "vmSize": "Standard_DS2_v2",
                                                 "osType": "Linux", "mode": "System"}]}}
                        """.formatted(name))
                    .when().put(RG + "/Microsoft.ContainerService/managedClusters/" + name + "?api-version=2024-04-01")
                    .then().statusCode(201);
        }

        given().post("/_admin/reset").then().statusCode(204);

        ArgumentCaptor<ManagedCluster> removed = ArgumentCaptor.forClass(ManagedCluster.class);
        verify(clusterManager, times(2)).removeCluster(removed.capture());
        assertThat(removed.getAllValues().stream().map(ManagedCluster::getName).toList(),
                containsInAnyOrder("resetone", "resettwo"));
    }

    @Test
    void aResetRemovesTheSharedAcrRegistryContainer() {
        given().contentType("application/json")
                .body("{\"location\":\"eastus\",\"sku\":{\"name\":\"Basic\"},\"properties\":{\"adminUserEnabled\":true}}")
                .when().put(RG + "/Microsoft.ContainerRegistry/registries/resetacr?api-version=2025-11-01")
                .then().statusCode(201);

        given().post("/_admin/reset").then().statusCode(204);

        verify(registryManager).shutdown();
    }
}
