package io.floci.az.services.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State store for the Azure SQL Database emulator.
 *
 * <p>Tracks logical servers and their child resources (databases, firewall rules).
 * Uses a dual-layer approach: an in-memory {@link ConcurrentHashMap} for O(1) reads
 * and a {@link StorageBackend} for pluggable persistence (memory / wal / hybrid /
 * persistent), configured via {@code FLOCI_AZ_STORAGE_SERVICES_SQL_MODE}.
 *
 * <p>When the emulator restarts with a persistent backend, server ARM metadata
 * (name, credentials, databases, firewall rules) is restored from disk.
 * Container runtime state ({@code containerId}, {@code hostPort}) is always
 * cleared on load — containers must be restarted on first use.
 *
 * <p>All public methods are thread-safe via {@code synchronized} blocks.
 */
@ApplicationScoped
public class SqlState {

    private static final Logger LOG = Logger.getLogger(SqlState.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** In-memory primary cache — source of truth for runtime reads. */
    private final ConcurrentHashMap<String, SqlServerEntry> servers = new ConcurrentHashMap<>();

    /** Pluggable persistence backend — write-through on every mutation. */
    private final StorageBackend<String, StoredObject> store;

    @Inject
    public SqlState(StorageFactory factory) {
        this.store = factory.create("sql");
        loadFromStore();
    }

    /** Package-private constructor for unit tests — bypasses CDI and uses the given backend. */
    SqlState(StorageBackend<String, StoredObject> store) {
        this.store = store;
        loadFromStore();
    }

    // ── Servers ───────────────────────────────────────────────────────────────

    public synchronized boolean serverExists(String serverName) {
        return servers.containsKey(key(serverName));
    }

    public synchronized void putServer(SqlServerEntry entry) {
        servers.put(key(entry.serverName()), entry);
        persist(entry);
    }

    public synchronized Optional<SqlServerEntry> getServer(String serverName) {
        return Optional.ofNullable(servers.get(key(serverName)));
    }

    public synchronized boolean removeServer(String serverName) {
        String k = key(serverName);
        boolean removed = servers.remove(k) != null;
        if (removed) store.delete(k);
        return removed;
    }

    public synchronized List<SqlServerEntry> listServers() {
        return List.copyOf(servers.values());
    }

    public synchronized List<SqlServerEntry> listServersBySubscription(String subscriptionId) {
        return servers.values().stream()
            .filter(s -> s.subscriptionId().equalsIgnoreCase(subscriptionId))
            .toList();
    }

    public synchronized List<SqlServerEntry> listServersByResourceGroup(String subscriptionId,
                                                                         String resourceGroup) {
        return servers.values().stream()
            .filter(s -> s.subscriptionId().equalsIgnoreCase(subscriptionId)
                      && s.resourceGroupName().equalsIgnoreCase(resourceGroup))
            .toList();
    }

    // ── Databases ─────────────────────────────────────────────────────────────

    public synchronized boolean databaseExists(String serverName, String dbName) {
        return getServer(serverName)
            .map(s -> s.databases().containsKey(key(dbName)))
            .orElse(false);
    }

    public synchronized void putDatabase(String serverName, SqlDatabaseEntry db) {
        getServer(serverName).ifPresent(s -> {
            s.databases().put(key(db.databaseName()), db);
            persist(s);
        });
    }

    public synchronized Optional<SqlDatabaseEntry> getDatabase(String serverName, String dbName) {
        return getServer(serverName)
            .map(s -> s.databases().get(key(dbName)));
    }

    public synchronized boolean removeDatabase(String serverName, String dbName) {
        return getServer(serverName).map(s -> {
            boolean removed = s.databases().remove(key(dbName)) != null;
            if (removed) persist(s);
            return removed;
        }).orElse(false);
    }

    public synchronized List<SqlDatabaseEntry> listDatabases(String serverName) {
        return getServer(serverName)
            .map(s -> List.copyOf(s.databases().values()))
            .orElse(List.of());
    }

    // ── Firewall rules ────────────────────────────────────────────────────────

    public synchronized void putFirewallRule(String serverName, SqlFirewallRule rule) {
        getServer(serverName).ifPresent(s -> {
            s.firewallRules().put(key(rule.name()), rule);
            persist(s);
        });
    }

    public synchronized Optional<SqlFirewallRule> getFirewallRule(String serverName, String ruleName) {
        return getServer(serverName)
            .map(s -> s.firewallRules().get(key(ruleName)));
    }

    public synchronized boolean removeFirewallRule(String serverName, String ruleName) {
        return getServer(serverName).map(s -> {
            boolean removed = s.firewallRules().remove(key(ruleName)) != null;
            if (removed) persist(s);
            return removed;
        }).orElse(false);
    }

    public synchronized List<SqlFirewallRule> listFirewallRules(String serverName) {
        return getServer(serverName)
            .map(s -> List.copyOf(s.firewallRules().values()))
            .orElse(List.of());
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    /**
     * Removes all servers (and their child resources) from both the in-memory
     * cache and the persistence backend.
     * Callers are responsible for stopping running containers beforehand.
     */
    public synchronized void clearAll() {
        servers.clear();
        store.clear();
    }

    // ── Persistence helpers ───────────────────────────────────────────────────

    /**
     * Serialises {@code entry} and writes it to the backend.
     * Silently logs on failure so a persistence hiccup never crashes the request.
     */
    private void persist(SqlServerEntry entry) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(entry);
            store.put(key(entry.serverName()), new StoredObject(
                key(entry.serverName()), data,
                Map.of("serverName", entry.serverName()),
                entry.createdAt(),
                UUID.randomUUID().toString()));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to persist SQL server entry: %s", entry.serverName());
        }
    }

    /**
     * Reads all entries from the backend into the in-memory cache.
     * Runtime-specific fields ({@code containerId}, {@code hostPort}) are always
     * cleared — containers do not survive an emulator restart.
     */
    private void loadFromStore() {
        int loaded = 0;
        for (String serverKey : store.keys()) {
            store.get(serverKey).ifPresent(obj -> {
                try {
                    SqlServerEntry e = MAPPER.readValue(obj.data(), SqlServerEntry.class);
                    // Re-wrap maps as ConcurrentHashMap (Jackson deserialises them as LinkedHashMap)
                    SqlServerEntry restored = new SqlServerEntry(
                        e.serverName(), e.subscriptionId(), e.resourceGroupName(),
                        e.location(), e.administratorLogin(), e.administratorLoginPassword(),
                        null, 0, "localhost",   // containerId / hostPort / host are always reset on load
                        new ConcurrentHashMap<>(e.tags() != null ? e.tags() : Map.of()),
                        new ConcurrentHashMap<>(e.databases() != null ? e.databases() : Map.of()),
                        new ConcurrentHashMap<>(e.firewallRules() != null ? e.firewallRules() : Map.of()),
                        e.createdAt());
                    servers.put(serverKey, restored);
                } catch (Exception ex) {
                    LOG.warnf(ex, "Failed to deserialize SQL server entry '%s' — skipping", serverKey);
                    store.delete(serverKey);
                }
            });
        }
        if (!servers.isEmpty()) {
            LOG.infof("Restored %d SQL server(s) from storage (containers will restart on next request)",
                servers.size());
        }
    }

    // ── Key helper ───────────────────────────────────────────────────────────

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase();
    }

    // ── Nested types ──────────────────────────────────────────────────────────

    /**
     * Represents a logical SQL Server (maps 1-to-1 with a Docker container).
     * <p>{@code containerId} and {@code hostPort} are runtime-only and are never
     * restored from persistent storage.
     */
    @RegisterForReflection
    public record SqlServerEntry(
            String serverName,
            String subscriptionId,
            String resourceGroupName,
            String location,
            String administratorLogin,
            String administratorLoginPassword,   // stored, never returned in GET responses
            String containerId,                   // null until container starts; not persisted
            int hostPort,                         // 0 until container starts; not persisted
            String host,                          // reachable host: "localhost" or the container name on a shared Docker network; not persisted
            Map<String, String> tags,
            Map<String, SqlDatabaseEntry> databases,
            Map<String, SqlFirewallRule> firewallRules,
            Instant createdAt
    ) {
        public String armId() {
            return String.format(
                "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Sql/servers/%s",
                subscriptionId, resourceGroupName, serverName);
        }

        public SqlServerEntry withContainer(String id, int port, String reachableHost) {
            return new SqlServerEntry(serverName, subscriptionId, resourceGroupName,
                location, administratorLogin, administratorLoginPassword,
                id, port, reachableHost, tags, databases, firewallRules, createdAt);
        }

        /**
         * The host an application uses to reach the data plane. Azure would expose
         * {@code {name}.database.windows.net}; floci-az returns the actually-reachable host —
         * {@code localhost} for host networking, or the container name when floci-az runs
         * inside Docker on a shared network.
         */
        public String fullyQualifiedDomainName() {
            return host != null && !host.isBlank() ? host : "localhost";
        }
    }

    /** Represents a SQL Database inside a logical server. */
    @RegisterForReflection
    public record SqlDatabaseEntry(
            String databaseName,
            String serverName,
            String collation,
            String edition,
            String sku,
            String status,
            String databaseId,
            Instant createdAt
    ) {
        public static SqlDatabaseEntry create(String dbName, String serverName,
                                               String collation, String edition, String sku) {
            return new SqlDatabaseEntry(
                dbName, serverName,
                collation != null && !collation.isBlank() ? collation : "SQL_Latin1_General_CP1_CI_AS",
                edition   != null && !edition.isBlank()   ? edition   : "Standard",
                sku       != null && !sku.isBlank()       ? sku       : "S0",
                "Online",
                UUID.randomUUID().toString(),
                Instant.now());
        }

        /** The master database is auto-created when a server is provisioned. */
        public static SqlDatabaseEntry master(String serverName) {
            return new SqlDatabaseEntry("master", serverName,
                "SQL_Latin1_General_CP1_CI_AS", "System", "System",
                "Online", UUID.randomUUID().toString(), Instant.now());
        }
    }

    /**
     * Represents a server-level firewall rule.
     * Rules are stored for API compliance but not enforced — all IPs are allowed in dev mode.
     */
    @RegisterForReflection
    public record SqlFirewallRule(
            String name,
            String startIpAddress,
            String endIpAddress
    ) {}
}
