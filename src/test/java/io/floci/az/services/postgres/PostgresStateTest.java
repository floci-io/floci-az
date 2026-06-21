package io.floci.az.services.postgres;

import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
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
 * Unit tests for {@link PostgresState} — pure in-memory CRUD, no Quarkus, no Docker.
 * Uses the package-private constructor to inject an {@link InMemoryStorage} backend directly.
 */
@DisplayName("PostgresState — in-memory state")
class PostgresStateTest {

    private PostgresState state;

    @BeforeEach
    void setup() {
        state = new PostgresState(new InMemoryStorage<>());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PostgresState.ServerEntry server(String name) {
        return server(name, "sub-001", "rg-test");
    }

    private PostgresState.ServerEntry server(String name, String sub, String rg) {
        return new PostgresState.ServerEntry(
            name, sub, rg, "eastus", "16",
            "psqladmin", "StrongPass1!",
            "Standard_B1ms", "Burstable", 32,
            "container-abc", 54320,
            Map.of(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
            Instant.now());
    }

    // ── Server CRUD ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("put and get server")
    void putAndGetServer() {
        state.putServer(server("myserver"));
        Optional<PostgresState.ServerEntry> found = state.getServer("myserver");
        assertTrue(found.isPresent());
        assertEquals("myserver", found.get().serverName());
        assertEquals("16", found.get().version());
        assertEquals("Burstable", found.get().skuTier());
    }

    @Test
    @DisplayName("server lookup is case-insensitive")
    void serverLookupCaseInsensitive() {
        state.putServer(server("MyServer"));
        assertTrue(state.serverExists("myserver"));
        assertTrue(state.serverExists("MYSERVER"));
    }

    @Test
    @DisplayName("remove server")
    void removeServer() {
        state.putServer(server("s1"));
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
        state.putServer(server("c", "sub-other", "rg-test"));

        List<PostgresState.ServerEntry> result = state.listServersBySubscription("sub-001");
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("list servers by resource group")
    void listServersByResourceGroup() {
        state.putServer(server("x"));
        state.putServer(server("y", "sub-001", "rg-other"));

        List<PostgresState.ServerEntry> result = state.listServersByResourceGroup("sub-001", "rg-test");
        assertEquals(1, result.size());
        assertEquals("x", result.get(0).serverName());
    }

    // ── Database CRUD ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("put and get database applies defaults")
    void putAndGetDatabase() {
        state.putServer(server("srv"));
        state.putDatabase("srv", PostgresState.DatabaseEntry.create("mydb", "srv", null, null));

        Optional<PostgresState.DatabaseEntry> found = state.getDatabase("srv", "mydb");
        assertTrue(found.isPresent());
        assertEquals("mydb", found.get().databaseName());
        assertEquals("UTF8", found.get().charset());          // default applied
        assertEquals("en_US.utf8", found.get().collation());  // default applied
    }

    @Test
    @DisplayName("database lookup is case-insensitive")
    void databaseLookupCaseInsensitive() {
        state.putServer(server("srv"));
        state.putDatabase("srv", PostgresState.DatabaseEntry.create("MyDB", "srv", null, null));
        assertTrue(state.databaseExists("srv", "mydb"));
        assertTrue(state.databaseExists("srv", "MYDB"));
    }

    @Test
    @DisplayName("remove database")
    void removeDatabase() {
        state.putServer(server("srv"));
        state.putDatabase("srv", PostgresState.DatabaseEntry.create("db1", "srv", null, null));
        assertTrue(state.removeDatabase("srv", "db1"));
        assertFalse(state.databaseExists("srv", "db1"));
    }

    @Test
    @DisplayName("list databases")
    void listDatabases() {
        state.putServer(server("srv"));
        state.putDatabase("srv", PostgresState.DatabaseEntry.create("app", "srv", null, null));
        state.putDatabase("srv", PostgresState.DatabaseEntry.create("reports", "srv", null, null));
        assertEquals(2, state.listDatabases("srv").size());
    }

    // ── Firewall rules ────────────────────────────────────────────────────────

    @Test
    @DisplayName("put, get and remove firewall rule")
    void firewallRuleCrud() {
        state.putServer(server("srv"));
        state.putFirewallRule("srv", new PostgresState.FirewallRule("AllowAll", "0.0.0.0", "255.255.255.255"));

        Optional<PostgresState.FirewallRule> found = state.getFirewallRule("srv", "AllowAll");
        assertTrue(found.isPresent());
        assertEquals("0.0.0.0", found.get().startIpAddress());

        assertTrue(state.removeFirewallRule("srv", "AllowAll"));
        assertTrue(state.getFirewallRule("srv", "AllowAll").isEmpty());
    }

    // ── Configurations ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("put, get and list configurations")
    void configurations() {
        state.putServer(server("srv"));
        state.putConfiguration("srv", "max_connections", "100");
        state.putConfiguration("srv", "work_mem", "8192");

        assertEquals("100", state.getConfiguration("srv", "max_connections").orElse(null));
        assertEquals(2, state.listConfigurations("srv").size());
    }

    // ── ServerEntry helpers ────────────────────────────────────────────────────

    @Test
    @DisplayName("ServerEntry.armId builds correct ARM resource ID")
    void armId() {
        String expected = "/subscriptions/sub-001/resourceGroups/rg-test/providers/"
            + "Microsoft.DBforPostgreSQL/flexibleServers/myserver";
        assertEquals(expected, server("myserver").armId());
    }

    @Test
    @DisplayName("ServerEntry.withContainer updates containerId and port immutably")
    void withContainer() {
        PostgresState.ServerEntry original = server("srv");
        PostgresState.ServerEntry updated  = original.withContainer("new-container", 54444);
        assertEquals("new-container", updated.containerId());
        assertEquals(54444, updated.hostPort());
        assertEquals("container-abc", original.containerId(), "original is immutable");
    }

    @Test
    @DisplayName("fullyQualifiedDomainName is localhost in dev mode")
    void fqdn() {
        assertEquals("localhost", server("srv").fullyQualifiedDomainName());
    }

    // ── Persistence cycle ─────────────────────────────────────────────────────

    @Test
    @DisplayName("state survives backend reload — ARM metadata restored, runtime fields cleared")
    void persistenceReload() {
        InMemoryStorage<String, StoredObject> sharedBackend = new InMemoryStorage<>();
        PostgresState original = new PostgresState(sharedBackend);

        original.putServer(server("persist-srv"));   // containerId="container-abc", hostPort=54320
        original.putDatabase("persist-srv",
            PostgresState.DatabaseEntry.create("mydb", "persist-srv", null, null));
        original.putFirewallRule("persist-srv",
            new PostgresState.FirewallRule("rule1", "10.0.0.1", "10.0.0.254"));
        original.putConfiguration("persist-srv", "max_connections", "200");

        // Simulate emulator restart
        PostgresState reloaded = new PostgresState(sharedBackend);

        assertTrue(reloaded.serverExists("persist-srv"), "server should survive reload");
        Optional<PostgresState.ServerEntry> entry = reloaded.getServer("persist-srv");
        assertTrue(entry.isPresent());
        assertEquals("psqladmin", entry.get().administratorLogin(), "login restored");
        assertEquals("16", entry.get().version(), "version restored");
        assertEquals(32, entry.get().storageSizeGB(), "storage restored");

        // Runtime fields cleared
        assertNull(entry.get().containerId(), "containerId null after reload");
        assertEquals(0, entry.get().hostPort(), "hostPort 0 after reload");

        // Child resources restored
        assertTrue(reloaded.databaseExists("persist-srv", "mydb"), "database restored");
        assertTrue(reloaded.getFirewallRule("persist-srv", "rule1").isPresent(), "firewall rule restored");
        assertEquals("200", reloaded.getConfiguration("persist-srv", "max_connections").orElse(null),
            "configuration restored");
    }

    @Test
    @DisplayName("clearAll removes entries from both in-memory cache and backend")
    void clearAllPurgesBackend() {
        InMemoryStorage<String, StoredObject> sharedBackend = new InMemoryStorage<>();
        PostgresState s = new PostgresState(sharedBackend);

        s.putServer(server("s1"));
        s.putServer(server("s2"));
        assertEquals(2, s.listServers().size());

        s.clearAll();
        assertEquals(0, s.listServers().size(), "in-memory cache cleared");

        PostgresState reloaded = new PostgresState(sharedBackend);
        assertEquals(0, reloaded.listServers().size(), "backend cleared — reload produces no entries");
    }
}
