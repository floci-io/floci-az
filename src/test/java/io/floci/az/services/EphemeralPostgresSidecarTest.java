package io.floci.az.services;

import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.services.cosmos.engine.CosmosApi;
import io.floci.az.services.cosmos.engine.CosmosLifecycleManager;
import io.floci.az.services.postgres.PostgresServerManager;
import io.floci.az.services.postgres.PostgresState;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL sidecars are ephemeral (removed before create and on stop, no data volume), so they
 * skip initdb's fsync and run the server without fsync or full-page writes.
 *
 * <p>The managers are driven with a mocked {@link ContainerLifecycleManager} that fails the start,
 * and the assertions check the spec Docker was asked to create.
 */
@QuarkusTest
@TestProfile(EphemeralPostgresSidecarTest.RealModeProfile.class)
@DisplayName("PostgreSQL sidecars: skip crash safety for ephemeral data")
class EphemeralPostgresSidecarTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.services.postgres.mocked", "false",
                "floci-az.services.postgres.startup-timeout-seconds", "1",
                "floci-az.services.cosmos.engines.postgresql.enabled", "true");
        }
    }

    private static final List<String> SERVER_ARGS = List.of("-c", "fsync=off", "-c", "full_page_writes=off");

    @InjectMock
    ContainerLifecycleManager containerManager;

    @Inject
    PostgresServerManager postgresManager;

    @Inject
    CosmosLifecycleManager cosmosManager;

    @Test
    @DisplayName("Azure Database for PostgreSQL server container skips crash safety")
    void flexibleServerSkipsCrashSafety() {
        when(containerManager.createAndStart(any(ContainerSpec.class)))
            .thenThrow(new RuntimeException("simulated: container start failed"));

        assertThrows(RuntimeException.class, () -> postgresManager.startServer(serverEntry()));

        assertSkipsCrashSafety(capturedSpec());
    }

    @Test
    @DisplayName("Cosmos DB for PostgreSQL engine container skips crash safety")
    void cosmosPostgresEngineSkipsCrashSafety() {
        when(containerManager.createAndStart(any(ContainerSpec.class)))
            .thenThrow(new RuntimeException("simulated: container start failed"));

        assertTrue(cosmosManager.getOrStart(CosmosApi.POSTGRESQL).isEmpty());

        assertSkipsCrashSafety(capturedSpec());
    }

    private ContainerSpec capturedSpec() {
        ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(containerManager).createAndStart(spec.capture());
        return spec.getValue();
    }

    private static void assertSkipsCrashSafety(ContainerSpec spec) {
        assertTrue(spec.env().contains("POSTGRES_INITDB_ARGS=--nosync"),
            "initdb must skip fsyncing the data directory: " + spec.env());
        assertEquals(SERVER_ARGS, spec.cmd(),
            "the image entrypoint runs postgres with these args because the command starts with '-'");
        assertTrue(spec.mounts().isEmpty() && spec.binds().isEmpty(),
            "fsync=off is only safe while the data directory dies with the container");
    }

    private static PostgresState.ServerEntry serverEntry() {
        return new PostgresState.ServerEntry(
            "ephemeral-pg", "sub", "rg", "eastus", "16",
            "pgadmin", "Str0ng!Passw0rd", "Standard_B1ms", "Burstable", 32,
            null, 0, null, Map.of(), Map.of(), Map.of(), Map.of(), Instant.now());
    }
}
