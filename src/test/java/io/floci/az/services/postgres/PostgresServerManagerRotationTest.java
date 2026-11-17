package io.floci.az.services.postgres;

import io.floci.az.core.docker.ContainerLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The real {@link PostgresServerManager#rotateAdminPassword} against a mocked container runtime:
 * what it sends to the container, and how it reads each way the exec can come back.
 */
@DisplayName("PostgreSQL admin password rotation inside the container")
class PostgresServerManagerRotationTest {

    private static final String OLD_PASSWORD = "Original_Strong123!";
    private static final String NEW_PASSWORD = "Rotated_Strong456!";
    private static final int UNFINISHED = -1;

    private ContainerLifecycleManager containers;
    private PostgresServerManager manager;

    @BeforeEach
    void setUp() {
        containers = mock(ContainerLifecycleManager.class);
        manager = new PostgresServerManager();
        manager.containerManager = containers;
    }

    @Test
    void aFailedStatementRaisesAnErrorThatCarriesNeitherPassword() {
        answerExec(new ContainerLifecycleManager.ExecResult(3,
                "ERROR:  syntax error near '" + NEW_PASSWORD + "'\nPGPASSWORD=" + OLD_PASSWORD + "\n"), null);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.rotateAdminPassword(entry(), NEW_PASSWORD));

        assertThat(failure.getMessage(), containsString("syntax error near '***'"));
        assertThat(failure.getMessage(), not(containsString(NEW_PASSWORD)));
        assertThat(failure.getMessage(), not(containsString(OLD_PASSWORD)));
    }

    @Test
    void theStatementIsBoundedByAServerSideTimeoutAndTheOldPasswordStaysOutOfTheCommandLine() {
        answerExec(new ContainerLifecycleManager.ExecResult(0, "ALTER ROLE\n"), null);

        manager.rotateAdminPassword(entry(), NEW_PASSWORD);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String[]> command = ArgumentCaptor.forClass(String[].class);
        verify(containers).execInContainer(eq("cid-1"), env.capture(), command.capture());
        assertThat(env.getValue(), hasItem("PGPASSWORD=" + OLD_PASSWORD));
        assertThat(env.getValue(), hasItem(startsWith("PGOPTIONS=-c statement_timeout=")));
        assertThat(String.join(" ", command.getValue()),
                not(containsString(OLD_PASSWORD)));
    }

    @Test
    void anUnfinishedStatementCountsAsAppliedOnceTheServerAcceptsTheNewPassword() {
        answerExec(new ContainerLifecycleManager.ExecResult(UNFINISHED, ""),
                new ContainerLifecycleManager.ExecResult(0, "1\n"));

        assertDoesNotThrow(() -> manager.rotateAdminPassword(entry(), NEW_PASSWORD));
    }

    @Test
    void anUnfinishedStatementCountsAsNotAppliedWhenTheServerRejectsTheNewPassword() {
        answerExec(new ContainerLifecycleManager.ExecResult(UNFINISHED, ""),
                new ContainerLifecycleManager.ExecResult(2,
                        "psql: error: FATAL:  password authentication failed for user \"pgadmin\"\n"));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.rotateAdminPassword(entry(), NEW_PASSWORD));

        assertThat(failure.getMessage(), containsString("was not applied"));
    }

    @Test
    void anUnfinishedStatementWhoseOutcomeCannotBeReadIsReportedAsUnknown() {
        answerExec(new ContainerLifecycleManager.ExecResult(UNFINISHED, ""),
                new ContainerLifecycleManager.ExecResult(2,
                        "psql: error: connection refused, PGPASSWORD=" + NEW_PASSWORD + "\n"));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.rotateAdminPassword(entry(), NEW_PASSWORD));

        assertThat(failure.getMessage(), containsString("outcome is unknown"));
        assertThat(failure.getMessage(), not(containsString(NEW_PASSWORD)));
    }

    @Test
    void addingAnAdministratorLoginCreatesASuperuserRoleAsTheCurrentAdmin() {
        List<String> sent = answerRoleExec(false, new ContainerLifecycleManager.ExecResult(0, "CREATE ROLE\n"), null);

        manager.createAdminRole(entry(), "new\"admin", NEW_PASSWORD);

        assertThat(sent.get(0), containsString("pg_roles WHERE rolname = 'new\"admin'"));
        assertThat(sent.get(1), containsString("-U pgadmin"));
        assertThat(sent.get(1),
                containsString("CREATE ROLE \"new\"\"admin\" WITH LOGIN SUPERUSER PASSWORD '" + NEW_PASSWORD + "'"));
    }

    @Test
    void aRetryAfterALostResultSetsUpTheRoleThatIsAlreadyThere() {
        List<String> sent = answerRoleExec(true, new ContainerLifecycleManager.ExecResult(0, "ALTER ROLE\n"), null);

        assertDoesNotThrow(() -> manager.createAdminRole(entry(), "newadmin", NEW_PASSWORD));

        assertThat(sent.get(1),
                containsString("ALTER ROLE \"newadmin\" WITH LOGIN SUPERUSER PASSWORD '" + NEW_PASSWORD + "'"));
    }

    @Test
    void anUnfinishedRoleCreationIsSettledByLoggingInAsTheNewRole() {
        answerRoleExec(false, new ContainerLifecycleManager.ExecResult(UNFINISHED, ""),
                new ContainerLifecycleManager.ExecResult(2,
                        "psql: error: FATAL:  password authentication failed for user \"newadmin\"\n"));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.createAdminRole(entry(), "newadmin", NEW_PASSWORD));

        assertThat(failure.getMessage(), containsString("Adding administrator login timed out"));
        assertThat(failure.getMessage(), containsString("was not applied"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        verify(containers, times(3)).execInContainer(eq("cid-1"), env.capture(), any(String[].class));
        assertThat(env.getAllValues().get(2), hasItem("PGUSER=newadmin"));
    }

    /**
     * Answers the role lookup with whether the role exists, the statement with {@code statement}, and the login
     * that settles an unfinished statement with {@code login}; returns each command as it is sent.
     */
    private List<String> answerRoleExec(boolean roleExists, ContainerLifecycleManager.ExecResult statement,
                                        ContainerLifecycleManager.ExecResult login) {
        List<String> sent = new ArrayList<>();
        when(containers.execInContainer(eq("cid-1"), anyList(), any(String[].class)))
                .thenAnswer((InvocationOnMock invocation) -> {
                    List<String> env = invocation.getArgument(1);
                    Object[] args = invocation.getArguments();
                    StringBuilder command = new StringBuilder();
                    for (int i = 2; i < args.length; i++) {
                        command.append(args[i] instanceof String[] parts ? String.join(" ", parts) : args[i]).append(' ');
                    }
                    sent.add(command.toString());
                    if (env.contains("PGPASSWORD=" + NEW_PASSWORD)) {
                        return login;
                    }
                    return command.indexOf("pg_roles") >= 0
                            ? new ContainerLifecycleManager.ExecResult(0, roleExists ? "1\n" : "")
                            : statement;
                });
        return sent;
    }

    /** The login that settles an unfinished rotation is the only exec that authenticates with the new password. */
    private void answerExec(ContainerLifecycleManager.ExecResult rotation, ContainerLifecycleManager.ExecResult login) {
        when(containers.execInContainer(eq("cid-1"), anyList(), any(String[].class)))
                .thenAnswer((InvocationOnMock invocation) -> {
                    List<String> env = invocation.getArgument(1);
                    return env.contains("PGPASSWORD=" + NEW_PASSWORD) ? login : rotation;
                });
    }

    private static PostgresState.ServerEntry entry() {
        return new PostgresState.ServerEntry(
                "rotate-pg", "sub", "rg", "eastus", "16", "pgadmin", OLD_PASSWORD,
                "Standard_B1ms", "Burstable", 32, "cid-1", 5432, "localhost",
                Map.of(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                Instant.now());
    }
}
