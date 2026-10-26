package io.floci.az.services.sql;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.floci.az.config.EmulatorConfig.SqlDataPlaneProvider.MANAGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlHandlerConcurrencyTest {

    private static final String SERVER_PATH = "subscriptions/test-sub/resourceGroups/test-rg"
        + "/providers/Microsoft.Sql/servers/concurrentserver";
    private static final String BODY = "{\"location\":\"eastus\",\"properties\":{"
        + "\"administratorLogin\":\"sa\","
        + "\"administratorLoginPassword\":\"StrongPass1!\"}}";

    @Test
    void deleteWaitsForCreateToRegisterProvisioningOperation() throws Exception {
        EmulatorConfig config = managedConfig();
        SqlState state = new SqlState(SqlStateTest.over(new InMemoryStorage<>()));
        SqlServerManager serverManager = mock(SqlServerManager.class);
        SqlProvisioningService provisioningService = mock(SqlProvisioningService.class);
        CountDownLatch beginEntered = new CountDownLatch(1);
        CountDownLatch releaseBegin = new CountDownLatch(1);
        when(provisioningService.begin(any())).thenAnswer(invocation -> {
            SqlState.SqlServerEntry desired = invocation.getArgument(0);
            beginEntered.countDown();
            assertTrue(releaseBegin.await(5, TimeUnit.SECONDS));
            return SqlProvisioningService.SqlProvisioningOperation.inProgress(
                "operation-id", desired.serverName(), desired.location(), desired.armId());
        });
        SqlHandler handler = new SqlHandler(config, state, serverManager, provisioningService, names(), CloudDefaults.catalogOnly());

        ExecutorService requests = Executors.newFixedThreadPool(2);
        try {
            Future<Response> create = requests.submit(() -> handler.handle(request("PUT", BODY)));
            assertTrue(beginEntered.await(2, TimeUnit.SECONDS));

            Future<Response> delete = requests.submit(() -> handler.handle(request("DELETE", null)));
            assertThrows(TimeoutException.class, () -> delete.get(100, TimeUnit.MILLISECONDS));

            releaseBegin.countDown();
            assertEquals(202, create.get(2, TimeUnit.SECONDS).getStatus());
            assertEquals(204, delete.get(2, TimeUnit.SECONDS).getStatus());
            assertFalse(state.serverExists("concurrentserver"));
        } finally {
            releaseBegin.countDown();
            requests.shutdownNow();
        }
    }

    @Test
    void changedPutReprovisionsRestoredCreatingServerWithoutActiveOperation() {
        EmulatorConfig config = managedConfig();
        SqlState state = new SqlState(SqlStateTest.over(new InMemoryStorage<>()));
        state.putServer(new SqlState.SqlServerEntry(
            "concurrentserver", "test-sub", "test-rg", "eastus", "sa", "OldPass1!",
            null, 0, "localhost", "Creating", null, null, Map.of(),
            new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), Instant.now()));
        SqlServerManager serverManager = mock(SqlServerManager.class);
        SqlProvisioningService provisioningService = mock(SqlProvisioningService.class);
        when(provisioningService.activeOperation("concurrentserver")).thenReturn(Optional.empty());
        when(provisioningService.begin(any())).thenAnswer(invocation -> {
            SqlState.SqlServerEntry desired = invocation.getArgument(0);
            return SqlProvisioningService.SqlProvisioningOperation.inProgress(
                "operation-id", desired.serverName(), desired.location(), desired.armId());
        });
        SqlHandler handler = new SqlHandler(config, state, serverManager, provisioningService, names(), CloudDefaults.catalogOnly());

        Response response = handler.handle(request("PUT", BODY));

        assertEquals(202, response.getStatus());
        ArgumentCaptor<SqlState.SqlServerEntry> desired =
            ArgumentCaptor.forClass(SqlState.SqlServerEntry.class);
        verify(provisioningService).begin(desired.capture());
        assertEquals("StrongPass1!", desired.getValue().administratorLoginPassword());
    }

    @Test
    void anAccessOnlyChangeWhileTheServerIsCreatingConflictsInsteadOfBeingDropped() {
        EmulatorConfig config = managedConfig();
        SqlState state = new SqlState(SqlStateTest.over(new InMemoryStorage<>()));
        state.putServer(new SqlState.SqlServerEntry(
            "concurrentserver", "test-sub", "test-rg", "eastus", "sa", "StrongPass1!",
            null, 0, "localhost", "Creating", null, null, Map.of(),
            new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), Instant.now()));
        SqlProvisioningService provisioningService = mock(SqlProvisioningService.class);
        when(provisioningService.activeOperation("concurrentserver")).thenReturn(Optional.of(
            SqlProvisioningService.SqlProvisioningOperation.inProgress("operation-id", "concurrentserver", "eastus",
                state.getServer("concurrentserver").orElseThrow().armId())));
        SqlHandler handler = new SqlHandler(config, state, mock(SqlServerManager.class), provisioningService,
            names(), CloudDefaults.catalogOnly());

        assertEquals(202, handler.handle(request("PUT", BODY)).getStatus());
        Response changed = handler.handle(request("PUT", "{\"location\":\"eastus\",\"properties\":{"
            + "\"administratorLogin\":\"sa\",\"administratorLoginPassword\":\"StrongPass1!\","
            + "\"minimalTlsVersion\":\"1.2\"}}"));

        assertEquals(409, changed.getStatus());
        assertEquals("None", state.getServer("concurrentserver").orElseThrow().access().minimalTlsVersion());
    }

    @Test
    void concurrentPatchesOfDifferentFieldsOfOneSettingBothLand() throws Exception {
        SqlState state = new SqlState(SqlStateTest.over(new InMemoryStorage<>()));
        state.putServer(new SqlState.SqlServerEntry(
            "concurrentserver", "test-sub", "test-rg", "eastus", "sa", "StrongPass1!",
            null, 0, "localhost", "Ready", null, null, Map.of(),
            new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), Instant.now()));
        state.putDatabase("concurrentserver",
            SqlState.SqlDatabaseEntry.create("appdb", "concurrentserver", "c", "Standard", "S0"));
        SqlHandler handler = new SqlHandler(managedConfig(), state, mock(SqlServerManager.class),
            mock(SqlProvisioningService.class), names(), CloudDefaults.catalogOnly());
        String policy = SERVER_PATH + "/databases/appdb/backupShortTermRetentionPolicies/default";

        ExecutorService requests = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 50; round++) {
                state.updateDatabase("concurrentserver", "appdb", db -> db.withSetting(
                    SqlSettingType.BACKUP_SHORT_TERM_RETENTION, new SqlState.SqlSetting(Map.of())));
                CountDownLatch start = new CountDownLatch(1);
                Future<Response> days = requests.submit(() -> {
                    start.await();
                    return handler.handle(requestAt("PATCH", policy, "{\"properties\":{\"retentionDays\":14}}"));
                });
                Future<Response> interval = requests.submit(() -> {
                    start.await();
                    return handler.handle(requestAt("PATCH", policy,
                        "{\"properties\":{\"diffBackupIntervalInHours\":24}}"));
                });
                start.countDown();
                assertEquals(200, days.get(2, TimeUnit.SECONDS).getStatus());
                assertEquals(200, interval.get(2, TimeUnit.SECONDS).getStatus());

                Map<String, Object> stored = state.getDatabase("concurrentserver", "appdb").orElseThrow().settings()
                    .get(SqlSettingType.BACKUP_SHORT_TERM_RETENTION.key()).properties();
                assertEquals(14, ((Number) stored.get("retentionDays")).intValue(), "round " + round);
                assertEquals(24, ((Number) stored.get("diffBackupIntervalInHours")).intValue(), "round " + round);
            }
        } finally {
            requests.shutdownNow();
        }
    }

    /** A registry that reserves every name, as an empty one does. */
    private static GlobalNameRegistry names() {
        GlobalNameRegistry names = mock(GlobalNameRegistry.class);
        when(names.reserve(any(), any(), any(), any())).thenAnswer(invocation -> new GlobalNameRegistry.Reservation(
            GlobalNameRegistry.Outcome.RESERVED, invocation.getArgument(3), 1));
        return names;
    }

    private static EmulatorConfig managedConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.SqlServiceConfig sql = mock(EmulatorConfig.SqlServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(config.port()).thenReturn(4577);
        when(services.sql()).thenReturn(sql);
        when(sql.enabled()).thenReturn(true);
        when(sql.dataPlaneProvider()).thenReturn(MANAGED);
        return config;
    }

    private static AzureRequest request(String method, String body) {
        return requestAt(method, SERVER_PATH, body);
    }

    private static AzureRequest requestAt(String method, String path, String body) {
        HttpHeaders headers = mock(HttpHeaders.class);
        return new AzureRequest(method, "devstoreaccount1", "sql", path, headers,
            body == null ? null : new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
            Map.of("api-version", "2021-11-01"), null, false);
    }
}
