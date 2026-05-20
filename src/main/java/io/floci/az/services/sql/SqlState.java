package io.floci.az.services.sql;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory state for the Azure SQL Database emulator.
 *
 * <p>Tracks logical servers and their child resources (databases, firewall rules,
 * connection policy).  State is lost on restart — this matches the behaviour of
 * Docker-backed engines in floci-az (the SQL Server container itself is also
 * ephemeral unless given a named volume).
 *
 * <p>All public methods are thread-safe via {@link ConcurrentHashMap} and
 * {@code synchronized} blocks where compound operations are needed.
 */
@ApplicationScoped
public class SqlState {

    // keyed by lower-cased server name for case-insensitive lookup
    private final ConcurrentHashMap<String, SqlServerEntry> servers = new ConcurrentHashMap<>();

    // ── Servers ───────────────────────────────────────────────────────────────

    public synchronized boolean serverExists(String serverName) {
        return servers.containsKey(key(serverName));
    }

    public synchronized void putServer(SqlServerEntry entry) {
        servers.put(key(entry.serverName()), entry);
    }

    public synchronized Optional<SqlServerEntry> getServer(String serverName) {
        return Optional.ofNullable(servers.get(key(serverName)));
    }

    public synchronized boolean removeServer(String serverName) {
        return servers.remove(key(serverName)) != null;
    }

    public synchronized List<SqlServerEntry> listServers() {
        return new ArrayList<>(servers.values());
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
        getServer(serverName).ifPresent(s -> s.databases().put(key(db.databaseName()), db));
    }

    public synchronized Optional<SqlDatabaseEntry> getDatabase(String serverName, String dbName) {
        return getServer(serverName)
            .map(s -> s.databases().get(key(dbName)));
    }

    public synchronized boolean removeDatabase(String serverName, String dbName) {
        return getServer(serverName)
            .map(s -> s.databases().remove(key(dbName)) != null)
            .orElse(false);
    }

    public synchronized List<SqlDatabaseEntry> listDatabases(String serverName) {
        return getServer(serverName)
            .map(s -> List.copyOf(s.databases().values()))
            .orElse(List.of());
    }

    // ── Firewall rules ────────────────────────────────────────────────────────

    public synchronized void putFirewallRule(String serverName, SqlFirewallRule rule) {
        getServer(serverName).ifPresent(s -> s.firewallRules().put(key(rule.name()), rule));
    }

    public synchronized Optional<SqlFirewallRule> getFirewallRule(String serverName, String ruleName) {
        return getServer(serverName)
            .map(s -> s.firewallRules().get(key(ruleName)));
    }

    public synchronized boolean removeFirewallRule(String serverName, String ruleName) {
        return getServer(serverName)
            .map(s -> s.firewallRules().remove(key(ruleName)) != null)
            .orElse(false);
    }

    public synchronized List<SqlFirewallRule> listFirewallRules(String serverName) {
        return getServer(serverName)
            .map(s -> List.copyOf(s.firewallRules().values()))
            .orElse(List.of());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase();
    }

    // ── Nested types ──────────────────────────────────────────────────────────

    /**
     * Represents a logical SQL Server (maps 1-to-1 with a Docker container).
     */
    public record SqlServerEntry(
            String serverName,
            String subscriptionId,
            String resourceGroupName,
            String location,
            String administratorLogin,
            String administratorLoginPassword,   // stored, never returned in GET responses
            String containerId,                   // null until container starts
            int hostPort,                         // 0 until container starts
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

        public SqlServerEntry withContainer(String id, int port) {
            return new SqlServerEntry(serverName, subscriptionId, resourceGroupName,
                location, administratorLogin, administratorLoginPassword,
                id, port, tags, databases, firewallRules, createdAt);
        }

        /** Returns the FQDN as Azure would expose it. In local dev this is always localhost. */
        public String fullyQualifiedDomainName() {
            return "localhost";
        }
    }

    /**
     * Represents a SQL Database inside a logical server.
     */
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
    public record SqlFirewallRule(
            String name,
            String startIpAddress,
            String endIpAddress
    ) {}
}
