package io.floci.az.services.mysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
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
 * Unit tests for {@link MySqlState} — pure in-memory CRUD, no Quarkus, no Docker.
 */
@DisplayName("MySqlState — in-memory state")
class MySqlStateTest {

    private MySqlState state;

    @BeforeEach
    void setup() {
        state = new MySqlState(over(new InMemoryStorage<>()));
    }

    /** A factory whose every store is {@code backend}. */
    static StorageFactory over(StorageBackend<String, StoredObject> backend) {
        return new StorageFactory(null) {
            @Override
            public synchronized StorageBackend<String, StoredObject> create(String serviceName) {
                return backend;
            }
        };
    }

    private MySqlState.ServerEntry server(String name) {
        return server(name, "sub-001", "rg-test");
    }

    private MySqlState.ServerEntry server(String name, String sub, String rg) {
        return new MySqlState.ServerEntry(
            name, sub, rg, "eastus", "8.0.21",
            "mysqladmin", "StrongPass1!",
            "Standard_B1ms", "Burstable", 20,
            "container-abc", 33060, "localhost",
            Map.of(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
            Instant.now());
    }

    @Test
    @DisplayName("put and get server")
    void putAndGetServer() {
        state.putServer(server("myserver"));
        Optional<MySqlState.ServerEntry> found = state.getServer("myserver");
        assertTrue(found.isPresent());
        assertEquals("myserver", found.get().serverName());
        assertEquals("8.0.21", found.get().version());
    }

    @Test
    @DisplayName("serverExists returns false for unknown")
    void serverExistsFalseForUnknown() {
        assertFalse(state.serverExists("ghost"));
    }

    @Test
    @DisplayName("serverExists true after put")
    void serverExistsTrueAfterPut() {
        state.putServer(server("s1"));
        assertTrue(state.serverExists("s1"));
    }

    @Test
    @DisplayName("removeServer deletes and returns true")
    void removeServer() {
        state.putServer(server("del"));
        assertTrue(state.removeServer("del"));
        assertFalse(state.serverExists("del"));
    }

    @Test
    @DisplayName("listServers returns all")
    void listServers() {
        state.putServer(server("a"));
        state.putServer(server("b"));
        assertEquals(2, state.listServers().size());
    }

    @Test
    @DisplayName("listServersBySubscription filters correctly")
    void listBySubscription() {
        state.putServer(server("s1", "sub-a", "rg"));
        state.putServer(server("s2", "sub-b", "rg"));
        assertEquals(1, state.listServersBySubscription("sub-a").size());
    }

    @Test
    @DisplayName("database CRUD works")
    void databaseCrud() {
        state.putServer(server("host"));
        MySqlState.DatabaseEntry db = MySqlState.DatabaseEntry.create("appdb", "host", "utf8mb4", "utf8mb4_general_ci");
        state.putDatabase("host", db);

        assertTrue(state.databaseExists("host", "appdb"));
        Optional<MySqlState.DatabaseEntry> found = state.getDatabase("host", "appdb");
        assertTrue(found.isPresent());
        assertEquals("utf8mb4", found.get().charset());

        state.removeDatabase("host", "appdb");
        assertFalse(state.databaseExists("host", "appdb"));
    }

    @Test
    @DisplayName("firewall rule CRUD works")
    void firewallRuleCrud() {
        state.putServer(server("fw"));
        MySqlState.FirewallRule rule = new MySqlState.FirewallRule("AllowAll", "0.0.0.0", "255.255.255.255");
        state.putFirewallRule("fw", rule);

        assertTrue(state.getFirewallRule("fw", "AllowAll").isPresent());
        List<MySqlState.FirewallRule> rules = state.listFirewallRules("fw");
        assertEquals(1, rules.size());

        state.removeFirewallRule("fw", "AllowAll");
        assertFalse(state.getFirewallRule("fw", "AllowAll").isPresent());
    }

    @Test
    @DisplayName("configuration put/get/list works")
    void configurationCrud() {
        state.putServer(server("cfg"));
        state.putConfiguration("cfg", "max_connections", "200");
        assertEquals("200", state.getConfiguration("cfg", "max_connections").orElse(""));
        assertEquals(1, state.listConfigurations("cfg").size());
    }

    @Test
    @DisplayName("armId uses Microsoft.DBforMySQL namespace")
    void armId() {
        MySqlState.ServerEntry s = server("myserver", "sub-123", "my-rg");
        String id = s.armId();
        assertTrue(id.contains("Microsoft.DBforMySQL/flexibleServers/myserver"));
        assertTrue(id.contains("sub-123"));
        assertTrue(id.contains("my-rg"));
    }

    @Test
    @DisplayName("DatabaseEntry defaults charset and collation")
    void databaseDefaults() {
        MySqlState.DatabaseEntry db = MySqlState.DatabaseEntry.create("d", "s", "", "");
        assertEquals("utf8mb4", db.charset());
        assertEquals("utf8mb4_general_ci", db.collation());
    }

    @Test
    @DisplayName("clear empties state")
    void clear() {
        state.putServer(server("x"));
        state.clear();
        assertEquals(0, state.listServers().size());
    }

    /** {@code live} with another version: a server update built from a read. */
    private static MySqlState.ServerEntry upgraded(MySqlState.ServerEntry live) {
        return new MySqlState.ServerEntry(live.serverName(), live.subscriptionId(), live.resourceGroupName(),
            live.location(), "8.4", live.administratorLogin(), live.administratorLoginPassword(),
            live.skuName(), live.skuTier(), live.storageSizeGB(), live.containerId(), live.hostPort(), live.host(),
            live.tags(), live.databases(), live.firewallRules(), live.configurations(), live.createdAt());
    }

    @Test
    void aServerUpdateKeepsTheChildrenWrittenSinceItsRead() {
        state.putServer(server("upd"));
        MySqlState.ServerEntry live = state.getServer("upd").orElseThrow();

        state.putDatabase("upd", MySqlState.DatabaseEntry.create("db1", "upd", null, null));
        state.putFirewallRule("upd", new MySqlState.FirewallRule("AllowAll", "0.0.0.0", "255.255.255.255"));
        state.putConfiguration("upd", "max_connections", "200");

        assertTrue(state.replaceServer(live, upgraded(live)));
        MySqlState.ServerEntry stored = state.getServer("upd").orElseThrow();
        assertEquals("8.4", stored.version());
        assertTrue(state.getDatabase("upd", "db1").isPresent());
        assertTrue(state.getFirewallRule("upd", "AllowAll").isPresent());
        assertEquals("200", state.getConfiguration("upd", "max_connections").orElse(""));
    }

    @Test
    void aServerUpdateDoesNotBringBackADeletedChild() {
        state.putServer(server("upd-del"));
        state.putDatabase("upd-del", MySqlState.DatabaseEntry.create("db1", "upd-del", null, null));
        MySqlState.ServerEntry live = state.getServer("upd-del").orElseThrow();

        state.removeDatabase("upd-del", "db1");

        assertTrue(state.replaceServer(live, upgraded(live)));
        assertFalse(state.getDatabase("upd-del", "db1").isPresent());
    }

    @Test
    void aServerStoredBeforeTheStateStartedIsFoundByName() throws Exception {
        InMemoryStorage<String, StoredObject> backend = new InMemoryStorage<>();
        new MySqlState(over(backend)).putServer(server("Canonical1"));
        MySqlState.ServerEntry legacy = server("Legacy1", "sub-legacy", "rg-legacy");
        byte[] data = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsBytes(legacy);
        backend.put("legacy1", new StoredObject("legacy1", data, Map.of(), Instant.now(), "legacy1"));

        MySqlState restarted = new MySqlState(over(backend));

        assertTrue(restarted.getServer("canonical1").isPresent());
        assertEquals("sub-legacy", restarted.getServer("LEGACY1").orElseThrow().subscriptionId());
    }

    @Test
    void aNameDeletedAndCreatedInAnotherScopeFindsTheNewServer() {
        MySqlState.ServerEntry first = server("moved", "sub-a", "rg-a");
        state.putServer(first);
        assertTrue(state.removeServer(MySqlState.serverId(first)));
        assertFalse(state.getServer("moved").isPresent());

        state.putServer(server("moved", "sub-b", "rg-b"));
        assertFalse(state.removeServer(MySqlState.serverId(first)));
        assertEquals("sub-b", state.getServer("moved").orElseThrow().subscriptionId());
    }
}
