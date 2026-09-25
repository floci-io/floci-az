package io.floci.az.services.arm;

import io.floci.az.services.acr.AcrRegistryManager;
import io.floci.az.services.postgres.PostgresServerManager;
import io.floci.az.services.redis.RedisCacheManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * A Redis cache, registry or PostgreSQL server deleted while its backing container is still starting must stay deleted:
 * the create that finishes afterwards must not write the record back and resurrect a name another
 * subscription may already have claimed.
 */
@QuarkusTest
@TestProfile(GlobalNameLifecycleRaceTest.RealContainers.class)
@DisplayName("A global name deleted during container startup stays deleted")
class GlobalNameLifecycleRaceTest {

    private static final String SUB = "dddddddd-0000-0000-0000-000000000001";
    private static final String RG = "rg-lifecycle";

    /** Container-backed mode, with the container managers mocked so startup can be held open. */
    public static class RealContainers implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.services.redis.mocked", "false",
                    "floci-az.services.acr.mocked", "false",
                    "floci-az.services.postgres.mocked", "false");
        }
    }

    @InjectMock
    RedisCacheManager cacheManager;

    @InjectMock
    AcrRegistryManager registryManager;

    @InjectMock
    PostgresServerManager postgresManager;

    @Test
    void aRedisCacheDeletedWhileItsContainerStartsStaysDeleted() throws Exception {
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            starting.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(cacheManager).startCache(any());

        String cache = "/subscriptions/" + SUB + "/resourceGroups/" + RG
                + "/providers/Microsoft.Cache/redis/lifecycle-redis?api-version=2023-08-01";
        assertDeletedDuringStartupStaysDeleted(cache,
                "{\"location\":\"eastus\",\"properties\":{\"sku\":{\"name\":\"Basic\",\"family\":\"C\",\"capacity\":0}}}",
                starting, release);
    }

    @Test
    void aRegistryDeletedWhileTheSharedRegistryStartsStaysDeleted() throws Exception {
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            starting.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(registryManager).ensureStarted();

        String registry = "/subscriptions/" + SUB + "/resourceGroups/" + RG
                + "/providers/Microsoft.ContainerRegistry/registries/lifecycleacr?api-version=2023-07-01";
        assertDeletedDuringStartupStaysDeleted(registry,
                "{\"location\":\"eastus\",\"sku\":{\"name\":\"Basic\"}}", starting, release);
    }

    @Test
    void aPostgresServerDeletedWhileItsContainerStartsStaysDeleted() throws Exception {
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            starting.countDown();
            release.await(10, TimeUnit.SECONDS);
            return invocation.getArgument(0);
        }).when(postgresManager).startServer(any());

        String server = "/subscriptions/" + SUB + "/resourceGroups/" + RG
                + "/providers/Microsoft.DBforPostgreSQL/flexibleServers/lifecycle-pg?api-version=2024-08-01";
        assertDeletedDuringStartupStaysDeleted(server,
                "{\"location\":\"eastus\",\"sku\":{\"name\":\"Standard_B1ms\",\"tier\":\"Burstable\"},"
                        + "\"properties\":{\"administratorLogin\":\"a\",\"administratorLoginPassword\":\"P@ssw0rd1234\","
                        + "\"version\":\"16\"}}",
                starting, release, 204);
    }

    private static void assertDeletedDuringStartupStaysDeleted(String url, String body,
                                                               CountDownLatch starting, CountDownLatch release)
            throws Exception {
        assertDeletedDuringStartupStaysDeleted(url, body, starting, release, 202);
    }

    private static void assertDeletedDuringStartupStaysDeleted(String url, String body,
                                                               CountDownLatch starting, CountDownLatch release,
                                                               int deleteStatus)
            throws Exception {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .put("/subscriptions/" + SUB + "/resourceGroups/" + RG + "?api-version=2021-04-01");

        CompletableFuture<Integer> create = CompletableFuture.supplyAsync(
                () -> given().contentType("application/json").body(body).put(url).statusCode());
        assertTrue(starting.await(10, TimeUnit.SECONDS), "container startup never began");

        given().delete(url).then().statusCode(deleteStatus);
        release.countDown();
        create.get(20, TimeUnit.SECONDS);

        given().get(url).then().statusCode(404);
    }
}
