package io.floci.az.services.sql;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Reconciles desired managed SQL server state outside ARM request threads. */
@ApplicationScoped
public class SqlProvisioningService {

    private static final Logger LOG = Logger.getLogger(SqlProvisioningService.class);
    private static final int WORKER_COUNT = 2;
    private static final int QUEUE_CAPACITY = 64;

    private final SqlState state;
    private final SqlServerManager serverManager;
    private final ExecutorService executor;
    private final Map<String, SqlProvisioningOperation> operations = new ConcurrentHashMap<>();
    private final Map<String, String> activeOperations = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();

    @Inject
    public SqlProvisioningService(SqlState state, SqlServerManager serverManager) {
        this(state, serverManager, newExecutor());
    }

    SqlProvisioningService(SqlState state, SqlServerManager serverManager, ExecutorService executor) {
        this.state = state;
        this.serverManager = serverManager;
        this.executor = executor;
    }

    public synchronized SqlProvisioningOperation begin(SqlState.SqlServerEntry desired) {
        Optional<SqlProvisioningOperation> active = activeOperation(desired.serverName());
        if (active.isPresent()) {
            return active.get();
        }

        String operationId = UUID.randomUUID().toString();
        SqlProvisioningOperation operation = SqlProvisioningOperation.inProgress(
            operationId, desired.serverName(), desired.location(), desired.armId());
        operations.put(operationId, operation);
        activeOperations.put(key(desired.serverName()), operationId);

        try {
            FutureTask<Void> task = new FutureTask<>(() -> {
                provision(operationId, desired);
                return null;
            });
            futures.put(operationId, task);
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            futures.remove(operationId);
            fail(operationId, desired, "ProvisioningQueueFull",
                "SQL provisioning queue is full. Retry the request later.", e);
        }
        return operations.get(operationId);
    }

    public Optional<SqlProvisioningOperation> get(String operationId) {
        return Optional.ofNullable(operations.get(operationId));
    }

    public Optional<SqlProvisioningOperation> activeOperation(String serverName) {
        return Optional.ofNullable(activeOperations.get(key(serverName)))
            .flatMap(this::get);
    }

    public synchronized void cancel(String serverName) {
        String operationId = activeOperations.remove(key(serverName));
        if (operationId == null) {
            return;
        }
        Future<?> future = futures.remove(operationId);
        if (future != null) {
            future.cancel(true);
        }
        operations.computeIfPresent(operationId,
            (id, operation) -> operation.complete("Canceled", "OperationCanceled",
                "SQL server provisioning was canceled."));
    }

    public synchronized void clear() {
        List.copyOf(activeOperations.keySet()).forEach(this::cancel);
        operations.clear();
        futures.clear();
    }

    private void provision(String operationId, SqlState.SqlServerEntry desired) {
        try {
            SqlState.SqlServerEntry ready = serverManager.startServer(desired);
            boolean superseded;
            synchronized (this) {
                superseded = !isActive(operationId, desired.serverName())
                    || state.getServer(desired.serverName()).isEmpty();
                if (!superseded) {
                    state.putServer(ready);
                    state.putDatabase(desired.serverName(),
                        SqlState.SqlDatabaseEntry.master(desired.serverName()));
                    complete(operationId, "Succeeded", null, null);
                }
            }
            if (superseded) {
                stopSupersededServer(operationId, ready);
            }
        } catch (Exception e) {
            fail(operationId, desired, "ContainerStartFailed", e.getMessage(), e);
        } finally {
            futures.remove(operationId);
        }
    }

    private void stopSupersededServer(
            String operationId, SqlState.SqlServerEntry server) {
        try {
            serverManager.stopServer(server);
        } catch (Exception e) {
            LOG.errorf(e,
                "Failed to clean up superseded SQL Server container: server=%s operation=%s",
                server.serverName(), operationId);
        }
    }

    private synchronized void fail(String operationId, SqlState.SqlServerEntry desired,
                                   String code, String message, Exception cause) {
        if (!isActive(operationId, desired.serverName())) {
            return;
        }
        String safeMessage = message == null || message.isBlank()
            ? "SQL Server provisioning failed."
            : message;
        state.getServer(desired.serverName()).ifPresent(current -> state.putServer(
            current.withProvisioningState("Failed", code, safeMessage)));
        complete(operationId, "Failed", code, safeMessage);
        LOG.errorf(cause, "SQL Server provisioning failed: server=%s operation=%s",
            desired.serverName(), operationId);
    }

    private void complete(String operationId, String status, String code, String message) {
        SqlProvisioningOperation completed = operations.computeIfPresent(operationId,
            (id, operation) -> operation.complete(status, code, message));
        if (completed != null) {
            activeOperations.remove(key(completed.serverName()), operationId);
        }
    }

    private boolean isActive(String operationId, String serverName) {
        return operationId.equals(activeOperations.get(key(serverName)));
    }

    private static ExecutorService newExecutor() {
        return new ThreadPoolExecutor(
            WORKER_COUNT, WORKER_COUNT, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                Thread thread = new Thread(runnable, "floci-az-sql-provisioner");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    private static String key(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    @PreDestroy
    void shutdown() {
        clear();
        executor.shutdownNow();
    }

    public record SqlProvisioningOperation(
            String id,
            String serverName,
            String location,
            String resourceId,
            String status,
            Instant startTime,
            Instant endTime,
            String errorCode,
            String errorMessage
    ) {
        static SqlProvisioningOperation inProgress(String id, String serverName, String location,
                                                   String resourceId) {
            return new SqlProvisioningOperation(id, serverName, location, resourceId,
                "InProgress", Instant.now(), null, null, null);
        }

        SqlProvisioningOperation complete(String newStatus, String code, String message) {
            return new SqlProvisioningOperation(id, serverName, location, resourceId,
                newStatus, startTime, Instant.now(), code, message);
        }

        public boolean inProgress() {
            return "InProgress".equals(status);
        }
    }
}
