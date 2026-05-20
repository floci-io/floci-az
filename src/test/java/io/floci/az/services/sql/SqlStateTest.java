package io.floci.az.services.sql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SqlState} — pure in-memory CRUD, no Quarkus, no Docker.
 */
@DisplayName("SqlState — in-memory state")
class SqlStateTest {

    private SqlState state;

    @BeforeEach
    void setup() {
        state = new SqlState();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SqlState.SqlServerEntry server(String name) {
        return new SqlState.SqlServerEntry(
            name, "sub-001", "rg-test", "eastus",
            "sa", "StrongPass1!",
            "container-abc", 14330,
            Map.of(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), Instant.now());
    }

    // ── Server CRUD ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("put and get server")
    void putAndGetServer() {
        state.putServer(server("myserver"));
        Optional<SqlState.SqlServerEntry> found = state.getServer("myserver");
        assertTrue(found.isPresent());
        assertEquals("myserver", found.get().serverName());
    }

    @Test
    @DisplayName("server lookup is case-insensitive")
    void serverLookupCaseInsensitive() {
        state.putServer(server("MyServer"));
        assertTrue(state.serverExists("myserver"));
        assertTrue(state.serverExists("MYSERVER"));
        assertTrue(state.serverExists("MyServer"));
    }

    @Test
    @DisplayName("get missing server returns empty")
    void getMissingServer() {
        assertFalse(state.getServer("nonexistent").isPresent());
    }

    @Test
    @DisplayName("remove server")
    void removeServer() {
        state.putServer(server("s1"));
        assertTrue(state.serverExists("s1"));
        assertTrue(state.removeServer("s1"));
        assertFalse(state.serverExists("s1"));
    }

    @Test
    @DisplayName("remove missing server returns false")
    void removeMissingServer() {
        assertFalse(state.removeServer("ghost"));
    }

    @Test
    @DisplayName("list servers by subscription")
    void listServersBySubscription() {
        state.putServer(server("a"));
        state.putServer(server("b"));
        state.putServer(new SqlState.SqlServerEntry(
            "c", "sub-other", "rg-test", "eastus",
            "sa", "pass", null, 0, Map.of(),
            new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), Instant.now()));

        List<SqlState.SqlServerEntry> result = state.listServersBySubscription("sub-001");
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(s -> s.serverName().equals("a")));
        assertTrue(result.stream().anyMatch(s -> s.serverName().equals("b")));
    }

    @Test
    @DisplayName("list servers by resource group")
    void listServersByResourceGroup() {
        state.putServer(server("x"));
        state.putServer(new SqlState.SqlServerEntry(
            "y", "sub-001", "rg-other", "westus",
            "sa", "pass", null, 0, Map.of(),
            new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), Instant.now()));

        List<SqlState.SqlServerEntry> result =
            state.listServersByResourceGroup("sub-001", "rg-test");
        assertEquals(1, result.size());
        assertEquals("x", result.get(0).serverName());
    }

    // ── Database CRUD ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("put and get database")
    void putAndGetDatabase() {
        state.putServer(server("srv"));
        SqlState.SqlDatabaseEntry db = SqlState.SqlDatabaseEntry.create(
            "mydb", "srv", null, null, null);
        state.putDatabase("srv", db);

        Optional<SqlState.SqlDatabaseEntry> found = state.getDatabase("srv", "mydb");
        assertTrue(found.isPresent());
        assertEquals("mydb", found.get().databaseName());
        assertEquals("SQL_Latin1_General_CP1_CI_AS", found.get().collation()); // default applied
        assertEquals("Online", found.get().status());
    }

    @Test
    @DisplayName("database lookup is case-insensitive")
    void databaseLookupCaseInsensitive() {
        state.putServer(server("srv"));
        state.putDatabase("srv", SqlState.SqlDatabaseEntry.create("MyDB", "srv", null, null, null));
        assertTrue(state.databaseExists("srv", "mydb"));
        assertTrue(state.databaseExists("srv", "MYDB"));
    }

    @Test
    @DisplayName("get database on missing server returns empty")
    void getDatabaseMissingServer() {
        assertFalse(state.getDatabase("ghost", "db").isPresent());
    }

    @Test
    @DisplayName("remove database")
    void removeDatabase() {
        state.putServer(server("srv"));
        state.putDatabase("srv", SqlState.SqlDatabaseEntry.create("db1", "srv", null, null, null));
        assertTrue(state.databaseExists("srv", "db1"));
        assertTrue(state.removeDatabase("srv", "db1"));
        assertFalse(state.databaseExists("srv", "db1"));
    }

    @Test
    @DisplayName("list databases includes master")
    void listDatabasesIncludesMaster() {
        state.putServer(server("srv"));
        state.putDatabase("srv", SqlState.SqlDatabaseEntry.master("srv"));
        state.putDatabase("srv", SqlState.SqlDatabaseEntry.create("app", "srv", null, null, null));

        List<SqlState.SqlDatabaseEntry> dbs = state.listDatabases("srv");
        assertEquals(2, dbs.size());
        assertTrue(dbs.stream().anyMatch(d -> d.databaseName().equals("master")));
        assertTrue(dbs.stream().anyMatch(d -> d.databaseName().equals("app")));
    }

    // ── Firewall rules ────────────────────────────────────────────────────────

    @Test
    @DisplayName("put and get firewall rule")
    void putAndGetFirewallRule() {
        state.putServer(server("srv"));
        SqlState.SqlFirewallRule rule = new SqlState.SqlFirewallRule("AllowAll", "0.0.0.0", "255.255.255.255");
        state.putFirewallRule("srv", rule);

        Optional<SqlState.SqlFirewallRule> found = state.getFirewallRule("srv", "AllowAll");
        assertTrue(found.isPresent());
        assertEquals("0.0.0.0", found.get().startIpAddress());
    }

    @Test
    @DisplayName("remove firewall rule")
    void removeFirewallRule() {
        state.putServer(server("srv"));
        state.putFirewallRule("srv", new SqlState.SqlFirewallRule("r1", "1.2.3.4", "1.2.3.4"));
        assertTrue(state.removeFirewallRule("srv", "r1"));
        assertTrue(state.getFirewallRule("srv", "r1").isEmpty());
    }

    @Test
    @DisplayName("list firewall rules")
    void listFirewallRules() {
        state.putServer(server("srv"));
        state.putFirewallRule("srv", new SqlState.SqlFirewallRule("r1", "0.0.0.0", "0.0.0.0"));
        state.putFirewallRule("srv", new SqlState.SqlFirewallRule("r2", "10.0.0.0", "10.0.0.255"));

        List<SqlState.SqlFirewallRule> rules = state.listFirewallRules("srv");
        assertEquals(2, rules.size());
    }

    // ── SqlServerEntry helpers ────────────────────────────────────────────────

    @Test
    @DisplayName("SqlServerEntry.armId builds correct ARM resource ID")
    void armId() {
        SqlState.SqlServerEntry s = server("myserver");
        String expected = "/subscriptions/sub-001/resourceGroups/rg-test/providers/Microsoft.Sql/servers/myserver";
        assertEquals(expected, s.armId());
    }

    @Test
    @DisplayName("SqlServerEntry.withContainer updates containerId and port")
    void withContainer() {
        SqlState.SqlServerEntry original = server("srv");
        SqlState.SqlServerEntry updated  = original.withContainer("new-container", 14444);
        assertEquals("new-container", updated.containerId());
        assertEquals(14444, updated.hostPort());
        // original is immutable
        assertEquals("container-abc", original.containerId());
    }

    @Test
    @DisplayName("SqlDatabaseEntry.master creates system database")
    void masterDatabase() {
        SqlState.SqlDatabaseEntry master = SqlState.SqlDatabaseEntry.master("srv");
        assertEquals("master", master.databaseName());
        assertEquals("System", master.edition());
        assertEquals("Online", master.status());
    }
}
