package io.floci.az.services.sql;

import io.floci.az.core.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlProvisioningServiceTest {

    private SqlProvisioningService service;

    @AfterEach
    void shutdown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void provisionsOutsideCallerAndReusesActiveOperation() throws Exception {
        SqlState state = new SqlState(new InMemoryStorage<>());
        SqlServerManager manager = mock(SqlServerManager.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(manager.startServer(any())).thenAnswer(invocation -> {
            SqlState.SqlServerEntry desired = invocation.getArgument(0);
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timed out waiting to release provisioning");
            }
            return desired.withContainer("container-id", 14330, "localhost");
        });
        service = new SqlProvisioningService(state, manager, Executors.newSingleThreadExecutor());
        SqlState.SqlServerEntry desired = server("async-server");
        state.putServer(desired);

        SqlProvisioningService.SqlProvisioningOperation operation = service.begin(desired);

        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals("InProgress", operation.status());
        assertEquals(operation.id(), service.begin(desired).id());
        assertEquals("Creating", state.getServer("async-server").orElseThrow().provisioningState());

        release.countDown();
        SqlProvisioningService.SqlProvisioningOperation completed =
            awaitTerminal(operation.id(), Duration.ofSeconds(5));
        assertEquals("Succeeded", completed.status());
        assertNotNull(completed.endTime());
        assertEquals("Ready", state.getServer("async-server").orElseThrow().provisioningState());
        assertTrue(state.databaseExists("async-server", "master"));
        verify(manager).startServer(desired);
    }

    @Test
    void preservesFailedResourceAndOperation() throws Exception {
        SqlState state = new SqlState(new InMemoryStorage<>());
        SqlServerManager manager = mock(SqlServerManager.class);
        when(manager.startServer(any())).thenThrow(new IllegalStateException("engine unavailable"));
        service = new SqlProvisioningService(state, manager, Executors.newSingleThreadExecutor());
        SqlState.SqlServerEntry desired = server("failed-server");
        state.putServer(desired);

        SqlProvisioningService.SqlProvisioningOperation operation = service.begin(desired);
        SqlProvisioningService.SqlProvisioningOperation completed =
            awaitTerminal(operation.id(), Duration.ofSeconds(5));

        assertEquals("Failed", completed.status());
        assertEquals("ContainerStartFailed", completed.errorCode());
        assertEquals("engine unavailable", completed.errorMessage());
        SqlState.SqlServerEntry failed = state.getServer("failed-server").orElseThrow();
        assertEquals("Failed", failed.provisioningState());
        assertEquals("ContainerStartFailed", failed.failureCode());
    }

    @Test
    void canceledProvisioningCleansLateContainer() throws Exception {
        SqlState state = new SqlState(new InMemoryStorage<>());
        SqlServerManager manager = mock(SqlServerManager.class);
        CountDownLatch started = new CountDownLatch(1);
        Semaphore release = new Semaphore(0);
        when(manager.startServer(any())).thenAnswer(invocation -> {
            SqlState.SqlServerEntry desired = invocation.getArgument(0);
            started.countDown();
            release.acquireUninterruptibly();
            return desired.withContainer("late-container", 14330, "localhost");
        });
        service = new SqlProvisioningService(state, manager, Executors.newSingleThreadExecutor());
        SqlState.SqlServerEntry desired = server("canceled-server");
        state.putServer(desired);
        SqlProvisioningService.SqlProvisioningOperation operation = service.begin(desired);
        assertTrue(started.await(2, TimeUnit.SECONDS));

        service.cancel(desired.serverName());
        state.removeServer(desired.serverName());
        assertEquals("Canceled", service.get(operation.id()).orElseThrow().status());

        release.release();
        verify(manager, timeout(2_000)).stopServer(any());
    }

    @Test
    void boundsRetainedTerminalOperations() {
        SqlState state = new SqlState(new InMemoryStorage<>());
        SqlServerManager manager = mock(SqlServerManager.class);
        when(manager.startServer(any())).thenAnswer(invocation ->
            ((SqlState.SqlServerEntry) invocation.getArgument(0))
                .withContainer("container-id", 14330, "localhost"));
        var executor = mock(java.util.concurrent.ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any());
        service = new SqlProvisioningService(state, manager, executor);

        String firstOperationId = null;
        String lastOperationId = null;
        for (int index = 0; index < 257; index++) {
            SqlState.SqlServerEntry desired = server("server-" + index);
            state.putServer(desired);
            String operationId = service.begin(desired).id();
            if (firstOperationId == null) {
                firstOperationId = operationId;
            }
            lastOperationId = operationId;
        }

        assertTrue(service.get(firstOperationId).isEmpty());
        assertTrue(service.get(lastOperationId).isPresent());
    }

    private SqlProvisioningService.SqlProvisioningOperation awaitTerminal(
            String operationId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            SqlProvisioningService.SqlProvisioningOperation operation =
                service.get(operationId).orElseThrow();
            if (!operation.inProgress()) {
                return operation;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Operation did not complete within " + timeout);
    }

    private static SqlState.SqlServerEntry server(String name) {
        return new SqlState.SqlServerEntry(
            name, "sub-001", "rg-test", "eastus", "sa", "StrongPass1!",
            null, 0, "localhost", "Creating", null, null,
            Map.of(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), Instant.now());
    }
}
