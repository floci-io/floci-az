package io.floci.az.services.mysql;

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
 * State store for the Azure Database for MySQL (Flexible Server) emulator.
 *
 * <p>Tracks logical flexible servers and their child resources (databases, firewall
 * rules, configurations). Mirrors {@link io.floci.az.services.postgres.PostgresState}.
 */
@ApplicationScoped
public class MySqlState {

    private static final Logger LOG = Logger.getLogger(MySqlState.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ConcurrentHashMap<String, ServerEntry> servers = new ConcurrentHashMap<>();
    private final StorageBackend<String, StoredObject> store;

    @Inject
    public MySqlState(StorageFactory factory) {
        this.store = factory.create("mysql");
        loadFromStore();
    }

    MySqlState(StorageBackend<String, StoredObject> store) {
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

    // ── Configurations ─────────────────────────────────────────────────────────

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
            LOG.warnf(e, "Failed to persist MySQL server entry: %s", entry.serverName());
        }
    }

    private void loadFromStore() {
        for (String serverKey : store.keys()) {
            store.get(serverKey).ifPresent(obj -> {
                try {
                    ServerEntry e = MAPPER.readValue(obj.data(), ServerEntry.class);
                    ServerEntry restored = new ServerEntry(
                        e.serverName(), e.subscriptionId(), e.resourceGroupName(),
                        e.location(), e.version(), e.administratorLogin(), e.administratorLoginPassword(),
                        e.skuName(), e.skuTier(), e.storageSizeGB(),
                        null, 0, "localhost",
                        new ConcurrentHashMap<>(e.tags() != null ? e.tags() : Map.of()),
                        new ConcurrentHashMap<>(e.databases() != null ? e.databases() : Map.of()),
                        new ConcurrentHashMap<>(e.firewallRules() != null ? e.firewallRules() : Map.of()),
                        new ConcurrentHashMap<>(e.configurations() != null ? e.configurations() : Map.of()),
                        e.createdAt());
                    servers.put(serverKey, restored);
                } catch (Exception ex) {
                    LOG.warnf(ex, "Failed to deserialize MySQL server entry '%s' — skipping", serverKey);
                    store.delete(serverKey);
                }
            });
        }
        if (!servers.isEmpty()) {
            LOG.infof("Restored %d MySQL server(s) from storage (containers will restart on next request)",
                servers.size());
        }
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase();
    }

    // ── Nested types ──────────────────────────────────────────────────────────

    @RegisterForReflection
    public record ServerEntry(
            String serverName,
            String subscriptionId,
            String resourceGroupName,
            String location,
            String version,
            String administratorLogin,
            String administratorLoginPassword,
            String skuName,
            String skuTier,
            int storageSizeGB,
            String containerId,
            int hostPort,
            String host,
            Map<String, String> tags,
            Map<String, DatabaseEntry> databases,
            Map<String, FirewallRule> firewallRules,
            Map<String, String> configurations,
            Instant createdAt
    ) {
        public String armId() {
            return String.format(
                "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.DBforMySQL/flexibleServers/%s",
                subscriptionId, resourceGroupName, serverName);
        }

        public ServerEntry withContainer(String id, int port, String reachableHost) {
            return new ServerEntry(serverName, subscriptionId, resourceGroupName,
                location, version, administratorLogin, administratorLoginPassword,
                skuName, skuTier, storageSizeGB,
                id, port, reachableHost, tags, databases, firewallRules, configurations, createdAt);
        }

        public String fullyQualifiedDomainName() {
            return host != null && !host.isBlank() ? host : "localhost";
        }
    }

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
                charset != null && !charset.isBlank() ? charset : "utf8mb4",
                collation != null && !collation.isBlank() ? collation : "utf8mb4_general_ci",
                Instant.now());
        }
    }

    @RegisterForReflection
    public record FirewallRule(
            String name,
            String startIpAddress,
            String endIpAddress
    ) {}
}
