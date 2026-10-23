package io.floci.az.services.sql;

import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * State store for the Azure SQL Database emulator.
 *
 * <p>Tracks logical servers and their child resources (databases, firewall rules), which live inside their
 * server's record. Servers are kept in an {@link ArmResourceStore} over the {@code sql} backend, keyed by their
 * canonical ARM id, so persistence follows {@code FLOCI_AZ_STORAGE_SERVICES_SQL_MODE}. Before the store, servers
 * were kept under their lowercased name alone; those entries are read as they are and move to their canonical key
 * on their next write.
 *
 * <p>A server name is a global DNS name, so there is one server per name ({@code SqlArmSurface} declares it, and
 * the gate's {@code GlobalNameRule} refuses a second owner): the name-based methods below find the one server of a
 * name.
 *
 * <p>Container runtime state ({@code containerId}, {@code hostPort}, {@code host}) is not persisted: a server this
 * process did not write itself was read back from storage after a restart, so it is served without its runtime and,
 * when it was {@code Ready}, as {@code Creating}; its container restarts on the next request.
 *
 * <p>All public methods are thread-safe via {@code synchronized} blocks.
 */
@ApplicationScoped
public class SqlState {

    private static final Logger LOG = Logger.getLogger(SqlState.class);

    /** The backend name, unchanged since before the store, so persisted servers keep loading. */
    static final String STORE = "sql";

    private final ArmResourceStore<SqlServerEntry> store;

    /** The id of each server by lowercased name; null until first needed, rebuilt from the store. */
    private Map<String, ArmResourceId> idsByName;

    /** Canonical keys of the servers this process wrote, whose runtime fields are live. */
    private final Set<String> live = new HashSet<>();

    @Inject
    public SqlState(StorageFactory factory) {
        this(ArmResourceStore.create(factory, STORE, SqlServerEntry.class, SqlServerEntry::armId,
            SqlState::isLegacyKey));
    }

    private SqlState(ArmResourceStore<SqlServerEntry> store) {
        this.store = store;
        int restored = store.all().size();
        if (restored > 0) {
            LOG.infov("Restored {0} SQL server(s) from storage (containers will restart on next request)", restored);
        }
    }

    /** Before the store, a server was kept under its lowercased name, which has no {@code /}. */
    private static boolean isLegacyKey(String key) {
        return key.indexOf('/') < 0;
    }

    // ── Servers ───────────────────────────────────────────────────────────────

    public synchronized boolean serverExists(String serverName) {
        return getServer(serverName).isPresent();
    }

    /** Writes {@code entry}, replacing any other server of the same name: one server per name. */
    public synchronized void putServer(SqlServerEntry entry) {
        dropOtherServerNamed(entry.serverName(), idOf(entry));
        write(store.put(entry).id(), entry);
    }

    /**
     * Writes {@code change} applied to the server {@code id} names, or to empty when there is none, in one store
     * operation, so two concurrent creates of one server keep the first one's record; true when it was created.
     */
    public synchronized boolean createOrUpdateServer(ArmResourceId id,
                                                     Function<Optional<SqlServerEntry>, SqlServerEntry> change) {
        dropOtherServerNamed(id.name(), id);
        ArmResourceStore.Written<SqlServerEntry> written =
            store.createOrUpdate(id, existing -> change.apply(existing.map(this::served)));
        write(written.stored().id(), written.stored().value());
        return written.created();
    }

    public synchronized Optional<SqlServerEntry> getServer(String serverName) {
        return Optional.ofNullable(idsByName().get(key(serverName))).flatMap(this::getServer);
    }

    /** The server stored under {@code id}: a server of the same name in another scope is not it. */
    public synchronized Optional<SqlServerEntry> getServer(ArmResourceId id) {
        return store.get(id).map(stored -> served(stored.id(), stored.value()));
    }

    public synchronized boolean removeServer(String serverName) {
        ArmResourceId id = idsByName().remove(key(serverName));
        if (id == null) {
            return false;
        }
        live.remove(id.canonicalKey());
        return store.delete(id).isPresent();
    }

    public synchronized List<SqlServerEntry> listServers() {
        return served(store.all());
    }

    public synchronized List<SqlServerEntry> listServersBySubscription(String subscriptionId) {
        return served(store.list(new ArmScope(subscriptionId, null)));
    }

    public synchronized List<SqlServerEntry> listServersByResourceGroup(String subscriptionId,
                                                                         String resourceGroup) {
        return served(store.list(new ArmScope(subscriptionId, resourceGroup)));
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
            putServer(s);
        });
    }

    public synchronized Optional<SqlDatabaseEntry> getDatabase(String serverName, String dbName) {
        return getServer(serverName)
            .map(s -> s.databases().get(key(dbName)));
    }

    public synchronized boolean removeDatabase(String serverName, String dbName) {
        return getServer(serverName).map(s -> {
            boolean removed = s.databases().remove(key(dbName)) != null;
            if (removed) {
                putServer(s);
            }
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
            putServer(s);
        });
    }

    public synchronized Optional<SqlFirewallRule> getFirewallRule(String serverName, String ruleName) {
        return getServer(serverName)
            .map(s -> s.firewallRules().get(key(ruleName)));
    }

    public synchronized boolean removeFirewallRule(String serverName, String ruleName) {
        return getServer(serverName).map(s -> {
            boolean removed = s.firewallRules().remove(key(ruleName)) != null;
            if (removed) {
                putServer(s);
            }
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
     * Removes all servers (and their child resources) from the store, legacy entries included.
     * Callers are responsible for stopping running containers beforehand.
     */
    public synchronized void clear() {
        store.clear();
        idsByName = null;
        live.clear();
    }

    // ── Store helpers ─────────────────────────────────────────────────────────

    /** Removes the server named {@code serverName} when it is stored under an id other than {@code id}. */
    private void dropOtherServerNamed(String serverName, ArmResourceId id) {
        ArmResourceId previous = idsByName().get(key(serverName));
        if (previous != null && !previous.canonicalKey().equals(id.canonicalKey())) {
            store.delete(previous);
            live.remove(previous.canonicalKey());
        }
    }

    private void write(ArmResourceId id, SqlServerEntry entry) {
        idsByName().put(key(entry.serverName()), id);
        live.add(id.canonicalKey());
    }

    private Map<String, ArmResourceId> idsByName() {
        if (idsByName == null) {
            idsByName = new HashMap<>();
            for (ArmResourceStore.Stored<SqlServerEntry> stored : store.all()) {
                ArmResourceId previous = idsByName.putIfAbsent(key(stored.value().serverName()), stored.id());
                if (previous != null) {
                    LOG.warnv("SQL server name {0} is stored as both {1} and {2}; serving the first",
                        stored.value().serverName(), previous.id(), stored.id().id());
                }
            }
        }
        return idsByName;
    }

    private List<SqlServerEntry> served(List<ArmResourceStore.Stored<SqlServerEntry>> stored) {
        return stored.stream().map(entry -> served(entry.id(), entry.value())).toList();
    }

    private SqlServerEntry served(SqlServerEntry entry) {
        return served(idOf(entry), entry);
    }

    /**
     * The entry as callers see it: mutable child maps, and, for a server this process did not write, no runtime
     * fields, since its container did not survive the restart.
     */
    private SqlServerEntry served(ArmResourceId id, SqlServerEntry e) {
        boolean restored = !live.contains(id.canonicalKey());
        String provisioningState = e.provisioningState() != null ? e.provisioningState() : "Creating";
        return new SqlServerEntry(
            e.serverName(), e.subscriptionId(), e.resourceGroupName(),
            e.location(), e.administratorLogin(), e.administratorLoginPassword(),
            restored ? null : e.containerId(),
            restored ? 0 : e.hostPort(),
            restored ? "localhost" : e.host(),
            restored && "Ready".equals(provisioningState) ? "Creating" : provisioningState,
            e.failureCode(), e.failureMessage(),
            new ConcurrentHashMap<>(e.tags() != null ? e.tags() : Map.of()),
            new ConcurrentHashMap<>(e.databases() != null ? e.databases() : Map.of()),
            new ConcurrentHashMap<>(e.firewallRules() != null ? e.firewallRules() : Map.of()),
            e.createdAt());
    }

    private static ArmResourceId idOf(SqlServerEntry entry) {
        return ArmResourceId.parse(entry.armId()).orElseThrow(() -> new IllegalArgumentException(
            "SQL server '" + entry.serverName() + "' has no ARM id"));
    }

    // ── Key helper ───────────────────────────────────────────────────────────

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
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
            String provisioningState,
            String failureCode,
            String failureMessage,
            Map<String, String> tags,
            Map<String, SqlDatabaseEntry> databases,
            Map<String, SqlFirewallRule> firewallRules,
            Instant createdAt
    ) {
        public SqlServerEntry(String serverName, String subscriptionId, String resourceGroupName,
                              String location, String administratorLogin,
                              String administratorLoginPassword, String containerId, int hostPort,
                              String host, Map<String, String> tags,
                              Map<String, SqlDatabaseEntry> databases,
                              Map<String, SqlFirewallRule> firewallRules, Instant createdAt) {
            this(serverName, subscriptionId, resourceGroupName, location, administratorLogin,
                administratorLoginPassword, containerId, hostPort, host,
                containerId == null ? "Creating" : "Ready", null, null,
                tags, databases, firewallRules, createdAt);
        }

        public String armId() {
            return String.format(
                "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Sql/servers/%s",
                subscriptionId, resourceGroupName, serverName);
        }

        public SqlServerEntry withContainer(String id, int port, String reachableHost) {
            return new SqlServerEntry(serverName, subscriptionId, resourceGroupName,
                location, administratorLogin, administratorLoginPassword,
                id, port, reachableHost, "Ready", null, null,
                tags, databases, firewallRules, createdAt);
        }

        public SqlServerEntry withProvisioningState(String newState, String code, String message) {
            return new SqlServerEntry(serverName, subscriptionId, resourceGroupName,
                location, administratorLogin, administratorLoginPassword,
                containerId, hostPort, host, newState, code, message,
                tags, databases, firewallRules, createdAt);
        }

        /** Azure-shaped public hostname returned by ARM resource responses. */
        public String fullyQualifiedDomainName() {
            return serverName + ".database.windows.net";
        }

        /** Reachable host returned by the emulator-specific connection discovery endpoint. */
        public String dataPlaneHost() {
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
