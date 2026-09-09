package io.floci.az.services;

import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.services.postgres.PostgresServerManager;
import io.floci.az.services.postgres.PostgresState;
import io.floci.az.services.sql.SqlServerManager;
import io.floci.az.services.sql.SqlState;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A failed start must hand the configured host port back to the allocator.
 *
 * <p>This is the failure mode that makes {@code default-port} break silently rather than loudly.
 * The port is claimed before the container is built, so a start that throws anywhere before
 * ownership moves to {@code claimedPorts} leaves it reserved for the life of the process. Nothing
 * surfaces: the next create simply gets {@code claimOrZero() == 0} and falls back to an
 * OS-assigned port, so the configured port quietly stops being honoured.
 *
 * <p>The managers are driven directly with a mocked {@link ContainerLifecycleManager}, so the real
 * release path is what has to make this pass. PostgreSQL and SQL Server are covered because they
 * are the two that leaked; MySQL and MariaDB share the same structure.
 */
@QuarkusTest
@TestProfile(PortClaimReleaseTest.FixedPortProfile.class)
@DisplayName("Server managers — a failed start releases the claimed host port")
class PortClaimReleaseTest {

    private static final int PG_PORT = 15433;
    private static final int SQL_PORT = 14434;

    public static class FixedPortProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.services.postgres.mocked", "false",
                "floci-az.services.postgres.default-port", String.valueOf(PG_PORT),
                "floci-az.services.postgres.startup-timeout-seconds", "1",
                "floci-az.services.sql.mocked", "false",
                "floci-az.services.sql.accept-eula", "Y",
                "floci-az.services.sql.default-port", String.valueOf(SQL_PORT));
        }
    }

    @InjectMock
    ContainerLifecycleManager containerManager;

    @Inject
    PortAllocator portAllocator;

    @Inject
    PostgresServerManager postgresManager;

    @Inject
    SqlServerManager sqlManager;

    @Test
    @DisplayName("PostgreSQL releases the claim when the container fails to start")
    void postgresReleasesClaimOnStartFailure() {
        when(containerManager.createAndStart(any(ContainerSpec.class)))
            .thenThrow(new RuntimeException("simulated: driver failed programming external connectivity"));

        assertThrows(RuntimeException.class, () -> postgresManager.startServer(postgresEntry()));

        assertClaimWasMade(PG_PORT, 5432);
        assertEquals(PG_PORT, portAllocator.claimOrZero(PG_PORT),
            "the configured port must be claimable again, or default-port silently stops working");
        portAllocator.release(PG_PORT);
    }

    @Test
    @DisplayName("SQL Server releases the claim when the container fails to start")
    void sqlReleasesClaimOnStartFailure() {
        when(containerManager.createAndStart(any(ContainerSpec.class)))
            .thenThrow(new RuntimeException("simulated: port is already allocated"));

        assertThrows(RuntimeException.class, () -> sqlManager.startServer(sqlEntry()));

        assertClaimWasMade(SQL_PORT, 1433);
        assertEquals(SQL_PORT, portAllocator.claimOrZero(SQL_PORT),
            "the configured port must be claimable again, or default-port silently stops working");
        portAllocator.release(SQL_PORT);
    }

    @Test
    @DisplayName("PostgreSQL releases the claim when the port readback finds no endpoint")
    void postgresReleasesClaimWhenEndpointMissing() {
        // The container starts, but Docker reports no binding for the container port: the manager
        // throws on the readback, which is after createAndStart and before the claim is recorded.
        when(containerManager.createAndStart(any(ContainerSpec.class)))
            .thenReturn(new ContainerLifecycleManager.ContainerInfo("pg-container", Map.of()));

        assertThrows(RuntimeException.class, () -> postgresManager.startServer(postgresEntry()));

        assertClaimWasMade(PG_PORT, 5432);
        assertEquals(PG_PORT, portAllocator.claimOrZero(PG_PORT),
            "a failed port readback must not strand the claim");
        portAllocator.release(PG_PORT);
    }

    @Test
    @DisplayName("stopServer releases the claim even when the container teardown throws")
    void stopServerReleasesClaimWhenTeardownFails() {
        var endpoint = new ContainerLifecycleManager.EndpointInfo("localhost", PG_PORT);
        when(containerManager.createAndStart(any(ContainerSpec.class)))
            .thenReturn(new ContainerLifecycleManager.ContainerInfo("pg-running", Map.of(5432, endpoint)));

        PostgresState.ServerEntry started = postgresManager.startServer(postgresEntry());
        assertEquals(0, portAllocator.claimOrZero(PG_PORT), "the running server should still hold its port");

        // A daemon hiccup on teardown. Every caller of stopServer swallows this, and the delete
        // path has already dropped the server from state, so nothing would ever retry the release.
        doThrow(new RuntimeException("simulated: docker daemon unavailable"))
            .when(containerManager).stopAndRemove(any(), any());

        assertThrows(RuntimeException.class, () -> postgresManager.stopServer(started));

        assertEquals(PG_PORT, portAllocator.claimOrZero(PG_PORT),
            "a failed teardown must not strand the claim: the port is unreachable for every later create");
        portAllocator.release(PG_PORT);
    }

    /**
     * Without this the assertions below pass for the wrong reason: if the manager threw before it
     * ever claimed the port, nothing was reserved and reclaiming it trivially succeeds. Checking the
     * spec Docker was asked to create proves the claim was made and applied.
     */
    private void assertClaimWasMade(int expectedHostPort, int containerPort) {
        ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(containerManager).createAndStart(spec.capture());
        assertEquals(expectedHostPort, spec.getValue().portBindings().get(containerPort),
            "the manager must have claimed and bound the configured port for this test to mean anything");
    }

    private PostgresState.ServerEntry postgresEntry() {
        return new PostgresState.ServerEntry(
            "leak-test-pg", "sub", "rg", "eastus", "16",
            "pgadmin", "Str0ng!Passw0rd", "Standard_B1ms", "Burstable", 32,
            null, 0, null, Map.of(), Map.of(), Map.of(), Map.of(), Instant.now());
    }

    private SqlState.SqlServerEntry sqlEntry() {
        return new SqlState.SqlServerEntry(
            "leak-test-sql", "sub", "rg", "eastus", "sa", "FlociAz_Strong123!",
            null, 0, "localhost", "Creating", null, null,
            Map.of(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), Instant.now());
    }
}
