package io.floci.az.services.postgres;

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
import java.util.concurrent.ConcurrentHashMap;

/**
 * State store for the Azure Database for PostgreSQL (Flexible Server) emulator.
 *
 * <p>Tracks logical flexible servers and their child resources (databases, firewall
 * rules, server configurations). Uses a dual-layer approach: an in-memory
 * {@link ConcurrentHashMap} for O(1) reads and a {@link StorageBackend} for pluggable
 * persistence, configured via {@code FLOCI_AZ_STORAGE_SERVICES_POSTGRES_MODE}.
 *
 * <p>When the emulator restarts with a persistent backend, server ARM metadata is
 * restored from disk. Container runtime state ({@code containerId}, {@code hostPort})
 * is always cleared on load — containers must be restarted on first use.
 *
 * <p>Mirrors {@code SqlState}; all public methods are thread-safe via {@code synchronized}.
 */
@ApplicationScoped
public class PostgresState {

    private static final Logger LOG = Logger.getLogger(PostgresState.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** In-memory primary cache — source of truth for runtime reads. */
    private final ConcurrentHashMap<String, ServerEntry> servers = new ConcurrentHashMap<>();

    /** Pluggable persistence backend — write-through on every mutation. */
    private final StorageBackend<String, StoredObject> store;

    @Inject
    public PostgresState(StorageFactory factory) {
        this.store = factory.create("postgres");
        loadFromStore();
    }

    /** Package-private constructor for unit tests — bypasses CDI and uses the given backend. */
    PostgresState(StorageBackend<String, StoredObject> store) {
        this.store = store;
        loadFromStore();
    }

    // ── Servers ───────────────────────────────────────────────────────────────

    public synchronized boolean serverExists(String serverName) {
        return servers.containsKey(key(serverName));
    }

    public synchronized void putServer(ServerEntry entry) {
        servers.put(key(entry.serverName()), entry);
        persist(entry);
    }

    public synchronized Optional<ServerEntry> getServer(String serverName) {
        return Optional.ofNullable(servers.get(key(serverName)));
    }

    public synchronized boolean removeServer(String serverName) {
        String k = key(serverName);
        boolean removed = servers.remove(k) != null;
        if (removed) store.delete(k);
        return removed;
    }

    public synchronized List<ServerEntry> listServers() {
        return List.copyOf(servers.values());
    }

    public synchronized List<ServerEntry> listServersBySubscription(String subscriptionId) {
        return servers.values().stream()
            .filter(s -> s.subscriptionId().equalsIgnoreCase(subscriptionId))
            .toList();
    }

    public synchronized List<ServerEntry> listServersByResourceGroup(String subscriptionId,
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

    public synchronized void putDatabase(String serverName, DatabaseEntry db) {
        getServer(serverName).ifPresent(s -> {
            s.databases().put(key(db.databaseName()), db);
            persist(s);
        });
    }

    public synchronized Optional<DatabaseEntry> getDatabase(String serverName, String dbName) {
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

    public synchronized List<DatabaseEntry> listDatabases(String serverName) {
        return getServer(serverName)
            .map(s -> List.copyOf(s.databases().values()))
            .orElse(List.of());
    }

    // ── Firewall rules ────────────────────────────────────────────────────────

    public synchronized void putFirewallRule(String serverName, FirewallRule rule) {
        getServer(serverName).ifPresent(s -> {
            s.firewallRules().put(key(rule.name()), rule);
            persist(s);
        });
    }

    public synchronized Optional<FirewallRule> getFirewallRule(String serverName, String ruleName) {
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

    public synchronized List<FirewallRule> listFirewallRules(String serverName) {
        return getServer(serverName)
            .map(s -> List.copyOf(s.firewallRules().values()))
            .orElse(List.of());
    }

    // ── Server configurations ──────────────────────────────────────────────────

    public synchronized void putConfiguration(String serverName, String name, String value) {
        getServer(serverName).ifPresent(s -> {
            s.configurations().put(key(name), value);
            persist(s);
        });
    }

    public synchronized Optional<String> getConfiguration(String serverName, String name) {
        return getServer(serverName)
            .map(s -> s.configurations().get(key(name)));
    }

    public synchronized Map<String, String> listConfigurations(String serverName) {
        return getServer(serverName)
            .map(s -> Map.copyOf(s.configurations()))
            .orElse(Map.of());
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

    private void persist(ServerEntry entry) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(entry);
            store.put(key(entry.serverName()), new StoredObject(
                key(entry.serverName()), data,
                Map.of("serverName", entry.serverName()),
                entry.createdAt(),
                java.util.UUID.randomUUID().toString()));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to persist PostgreSQL server entry: %s", entry.serverName());
        }
    }

    private void loadFromStore() {
        for (String serverKey : store.keys()) {
            store.get(serverKey).ifPresent(obj -> {
                try {
                    ServerEntry e = MAPPER.readValue(obj.data(), ServerEntry.class);
                    // Re-wrap maps as ConcurrentHashMap and reset runtime-only fields.
                    ServerEntry restored = new ServerEntry(
                        e.serverName(), e.subscriptionId(), e.resourceGroupName(),
                        e.location(), e.version(), e.administratorLogin(), e.administratorLoginPassword(),
                        e.skuName(), e.skuTier(), e.storageSizeGB(),
                        null, 0, "localhost",   // containerId / hostPort / host are always reset on load
                        new ConcurrentHashMap<>(e.tags() != null ? e.tags() : Map.of()),
                        new ConcurrentHashMap<>(e.databases() != null ? e.databases() : Map.of()),
                        new ConcurrentHashMap<>(e.firewallRules() != null ? e.firewallRules() : Map.of()),
                        new ConcurrentHashMap<>(e.configurations() != null ? e.configurations() : Map.of()),
                        e.createdAt());
                    servers.put(serverKey, restored);
                } catch (Exception ex) {
                    LOG.warnf(ex, "Failed to deserialize PostgreSQL server entry '%s' — skipping", serverKey);
                    store.delete(serverKey);
                }
            });
        }
        if (!servers.isEmpty()) {
            LOG.infof("Restored %d PostgreSQL server(s) from storage (containers will restart on next request)",
                servers.size());
        }
    }

    // ── Key helper ───────────────────────────────────────────────────────────

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase();
    }

    // ── Nested types ──────────────────────────────────────────────────────────

    /**
     * Represents a logical PostgreSQL flexible server (maps 1-to-1 with a Docker container).
     * <p>{@code containerId} and {@code hostPort} are runtime-only and are never restored
     * from persistent storage.
     */
    @RegisterForReflection
    public record ServerEntry(
            String serverName,
            String subscriptionId,
            String resourceGroupName,
            String location,
            String version,
            String administratorLogin,
            String administratorLoginPassword,   // stored, never returned in GET responses
            String skuName,
            String skuTier,
            int storageSizeGB,
            String containerId,                   // null until container starts; not persisted
            int hostPort,                         // 0 until container starts; not persisted
            String host,                          // reachable host: "localhost" or the container name on a shared Docker network; not persisted
            Map<String, String> tags,
            Map<String, DatabaseEntry> databases,
            Map<String, FirewallRule> firewallRules,
            Map<String, String> configurations,
            Instant createdAt
    ) {
        public String armId() {
            return String.format(
                "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.DBforPostgreSQL/flexibleServers/%s",
                subscriptionId, resourceGroupName, serverName);
        }

        public ServerEntry withContainer(String id, int port, String reachableHost) {
            return new ServerEntry(serverName, subscriptionId, resourceGroupName,
                location, version, administratorLogin, administratorLoginPassword,
                skuName, skuTier, storageSizeGB,
                id, port, reachableHost, tags, databases, firewallRules, configurations, createdAt);
        }

        /**
         * The host an application uses to reach the data plane. Azure would expose
         * {@code {name}.postgres.database.azure.com}, which does not resolve locally; floci-az
         * returns the actually-reachable host instead — {@code localhost} for host networking,
         * or the container name when floci-az runs inside Docker on a shared network. The live
         * port is exposed separately (see the convenience {@code /connect} endpoint).
         */
        public String fullyQualifiedDomainName() {
            return host != null && !host.isBlank() ? host : "localhost";
        }
    }

    /** Represents a database inside a logical flexible server. */
    @RegisterForReflection
    public record DatabaseEntry(
            String databaseName,
            String serverName,
            String charset,
            String collation,
            Instant createdAt
    ) {
        public static DatabaseEntry create(String dbName, String serverName,
                                           String charset, String collation) {
            return new DatabaseEntry(
                dbName, serverName,
                charset   != null && !charset.isBlank()   ? charset   : "UTF8",
                collation != null && !collation.isBlank() ? collation : "en_US.utf8",
                Instant.now());
        }
    }

    /**
     * Represents a server-level firewall rule.
     * Rules are stored for API compliance but not enforced — all IPs are allowed in dev mode.
     */
    @RegisterForReflection
    public record FirewallRule(
            String name,
            String startIpAddress,
            String endIpAddress
    ) {}
}
