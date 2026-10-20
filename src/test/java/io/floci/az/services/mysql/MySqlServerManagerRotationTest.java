package io.floci.az.services.mysql;

import io.floci.az.core.docker.ContainerLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The real {@link MySqlServerManager#rotateAdminPassword} against a mocked container runtime:
 * what it sends to the container, and what its failure says.
 */
@DisplayName("MySQL admin password rotation inside the container")
class MySqlServerManagerRotationTest {

    private static final String OLD_PASSWORD = "Original_Strong123!";
    private static final String NEW_PASSWORD = "Rotated_Strong456!";

    private ContainerLifecycleManager containers;
    private MySqlServerManager manager;

    @BeforeEach
    void setUp() {
        containers = mock(ContainerLifecycleManager.class);
        manager = new MySqlServerManager();
        manager.containerManager = containers;
    }

    @Test
    void neitherPasswordAppearsOnTheCommandLine() {
        when(containers.execInContainer(eq("cid-1"), anyList(), any(String[].class)))
                .thenReturn(new ContainerLifecycleManager.ExecResult(0, ""));

        manager.rotateAdminPassword(entry(), NEW_PASSWORD);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String[]> command = ArgumentCaptor.forClass(String[].class);
        verify(containers).execInContainer(eq("cid-1"), env.capture(), command.capture());
        String argv = String.join(" ", command.getValue());
        assertThat(argv, not(containsString(OLD_PASSWORD)));
        assertThat(argv, not(containsString(NEW_PASSWORD)));
        assertThat(argv, containsString("mysql -uroot"));
        assertThat(env.getValue(), hasItem("MYSQL_PWD=" + OLD_PASSWORD));
        assertThat(String.join("\n", env.getValue()),
                containsString(MySqlServerManager.ROTATION_SQL_ENV + "=ALTER USER 'myadmin'@'%' IDENTIFIED BY '"
                        + NEW_PASSWORD + "'"));
    }

    @Test
    void aFailedRotationRaisesAnErrorThatCarriesNeitherPassword() {
        when(containers.execInContainer(eq("cid-1"), anyList(), any(String[].class)))
                .thenReturn(new ContainerLifecycleManager.ExecResult(1,
                        "ERROR 1064: near '" + NEW_PASSWORD + "' MYSQL_PWD=" + OLD_PASSWORD + "\n"));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.rotateAdminPassword(entry(), NEW_PASSWORD));

        assertThat(failure.getMessage(), containsString("near '***'"));
        assertThat(failure.getMessage(), not(containsString(NEW_PASSWORD)));
        assertThat(failure.getMessage(), not(containsString(OLD_PASSWORD)));
    }

    private static MySqlState.ServerEntry entry() {
        return new MySqlState.ServerEntry(
                "rotate-mysql", "sub", "rg", "eastus", "8.0.21", "myadmin", OLD_PASSWORD,
                "Standard_B1ms", "Burstable", 20, "cid-1", 3306, "localhost",
                Map.of(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                Instant.now());
    }
}
