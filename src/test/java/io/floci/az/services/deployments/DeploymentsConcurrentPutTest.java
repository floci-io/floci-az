package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.ArmRouter;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmTypeRegistry;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.ResourceGroups;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Deployments: two PUTs of one deployment at once start one run")
class DeploymentsConcurrentPutTest {

    private static final String SUB = "00000000-0000-0000-0000-0000000000c1";
    private static final String TENANT = "00000000-0000-0000-0000-0000000000c2";
    private static final String PATH = "subscriptions/" + SUB + "/providers/Microsoft.Resources/deployments/race";
    private static final String BODY = "{\"location\": \"westeurope\", \"properties\": {\"mode\": \"Incremental\", "
        + "\"template\": {\"resources\": []}}}";

    /** Both PUTs leave prepare together, after both passed the active-state check. */
    private final CyclicBarrier prepared = new CyclicBarrier(2);
    /** Holds every run, so the first one is still active when the second PUT stores its record. */
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger runs = new AtomicInteger();
    private final ExecutorService callers = Executors.newFixedThreadPool(2);
    private DeploymentsHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void handler() {
        AzureDirectory directory = mock(AzureDirectory.class);
        when(directory.tenantOfSubscription(SUB)).thenReturn(TENANT);
        when(directory.cloudOfTenant(TENANT)).thenReturn(CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD));
        when(directory.subscription(SUB)).thenReturn(Optional.empty());
        when(directory.tenant(TENANT)).thenReturn(Optional.empty());
        DeploymentEngine engine = new DeploymentEngine(mock(Instance.class), new ResourceGroups(null), directory,
                ArmTypeRegistry.empty(), new ObjectMapper(), CloudDefaults.catalogOnly()) {
            @Override
            public Prepared prepare(Request request) {
                Prepared prepared = super.prepare(request);
                await(() -> DeploymentsConcurrentPutTest.this.prepared.await(10, TimeUnit.SECONDS));
                return prepared;
            }

            @Override
            public Result run(Prepared prepared, BooleanSupplier canceled) {
                runs.incrementAndGet();
                await(() -> release.await(10, TimeUnit.SECONDS));
                return super.run(prepared, canceled);
            }
        };
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4577");
        handler = new DeploymentsHandler(config, engine, new ResourceGroups(null), new LinkedTemplates());
    }

    @AfterEach
    void stop() {
        release.countDown();
        callers.shutdownNow();
        handler.stop();
    }

    private interface Waiting {
        Object await() throws Exception;
    }

    private static void await(Waiting waiting) {
        try {
            waiting.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Response put() {
        return handler.handle(new AzureRequest("PUT", "arm", "deployments", PATH, null,
            new ByteArrayInputStream(BODY.getBytes(StandardCharsets.UTF_8)), Map.of("api-version", "2025-04-01"),
            null, false));
    }

    @Test
    void theSecondOfTwoConcurrentPutsIsDeploymentActive() throws Exception {
        Future<Response> first = callers.submit(this::put);
        Future<Response> second = callers.submit(this::put);
        List<Integer> statuses = new ArrayList<>(List.of(first.get(20, TimeUnit.SECONDS).getStatus(),
            second.get(20, TimeUnit.SECONDS).getStatus()));
        statuses.sort(Integer::compare);

        assertEquals(List.of(201, 409), statuses, "one PUT reserves the name, the other finds it active");
        for (int attempt = 0; attempt < 100 && runs.get() == 0; attempt++) {
            Thread.sleep(20);
        }
        Thread.sleep(100);
        assertEquals(1, runs.get(), "only the reserved deployment runs");
    }
}
