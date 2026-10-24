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
import java.util.function.UnaryOperator;

/**
 * State store for the Azure SQL Database emulator.
 *
 * <p>Tracks logical servers and their child resources (databases, firewall rules, settings), which live inside their
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
     * Writes the server fields of {@code updated} over the stored server of its name, whose databases and firewall
     * rules are kept: one written since {@code updated} was read is not undone. Writes {@code updated} as it is when
     * no server of its name is stored.
     */
    public synchronized void updateServer(SqlServerEntry updated) {
        putServer(getServer(updated.serverName()).map(updated::withChildrenOf).orElse(updated));
    }

    /**
     * Records a started container on the stored server of its name, which turns {@code Ready}; every other field is
     * the stored one, so a database, firewall rule or update written while the container started is kept. Empty,
     * and nothing written, when no server of that name is stored any more.
     */
    public synchronized Optional<SqlServerEntry> attachContainer(SqlServerEntry started) {
        Optional<SqlServerEntry> attached = getServer(started.serverName())
            .map(current -> current.withContainer(started.containerId(), started.hostPort(), started.host()));
        attached.ifPresent(this::putServer);
        return attached;
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

    /** What a create-or-update of a database wrote, and whether the database was new. */
    public record DatabaseWrite(SqlDatabaseEntry database, boolean created) {
    }

    /**
     * Writes {@code change} applied to the stored database {@code dbName} of server {@code serverName}, or to empty
     * when there is none, in one step, so a setting written in between is not overwritten from a stale read; empty,
     * and nothing written, when the server is not stored.
     */
    public synchronized Optional<DatabaseWrite> createOrUpdateDatabase(String serverName, String dbName,
            Function<Optional<SqlDatabaseEntry>, SqlDatabaseEntry> change) {
        return getServer(serverName).map(s -> {
            Optional<SqlDatabaseEntry> existing = Optional.ofNullable(s.databases().get(key(dbName)));
            SqlDatabaseEntry written = change.apply(existing);
            s.databases().put(key(dbName), written);
            putServer(s);
            return new DatabaseWrite(written, existing.isEmpty());
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

    /**
     * Writes {@code change} applied to the database {@code dbName} of server {@code serverName}; empty, and nothing
     * written, when either is not stored.
     */
    public synchronized Optional<SqlDatabaseEntry> updateDatabase(String serverName, String dbName,
                                                                  Function<SqlDatabaseEntry, SqlDatabaseEntry> change) {
        return getServer(serverName).flatMap(s -> Optional.ofNullable(s.databases().get(key(dbName))).map(db -> {
            SqlDatabaseEntry updated = change.apply(db);
            s.databases().put(key(dbName), updated);
            putServer(s);
            return updated;
        }));
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    /** The stored server setting of {@code type}; empty when it was never written (it then has its defaults). */
    public synchronized Optional<SqlSetting> getServerSetting(String serverName, SqlSettingType type) {
        return getServer(serverName).map(s -> s.settings().get(type.key()));
    }

    /**
     * Writes {@code change} applied to the stored server setting of {@code type} (null when never written) in one
     * step, so two concurrent writes each build on the other's result; empty, and nothing written, when the server
     * is not stored.
     */
    public synchronized Optional<SqlSetting> updateServerSetting(String serverName, SqlSettingType type,
                                                                 UnaryOperator<SqlSetting> change) {
        return getServer(serverName).map(s -> {
            SqlSetting written = change.apply(s.settings().get(type.key()));
            s.settings().put(type.key(), written);
            putServer(s);
            return written;
        });
    }

    public synchronized void putServerSetting(String serverName, SqlSettingType type, SqlSetting setting) {
        getServer(serverName).ifPresent(s -> {
            s.settings().put(type.key(), setting);
            putServer(s);
        });
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
            e.createdAt(),
            e.access() != null ? e.access().orElse(SqlServerAccess.DEFAULT) : SqlServerAccess.DEFAULT,
            new ConcurrentHashMap<>(e.settings() != null ? e.settings() : Map.of()));
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
            Instant createdAt,
            SqlServerAccess access,
            Map<String, SqlSetting> settings
    ) {
        public SqlServerEntry(String serverName, String subscriptionId, String resourceGroupName,
                              String location, String administratorLogin,
                              String administratorLoginPassword, String containerId, int hostPort,
                              String host, String provisioningState, String failureCode,
                              String failureMessage, Map<String, String> tags,
                              Map<String, SqlDatabaseEntry> databases,
                              Map<String, SqlFirewallRule> firewallRules, Instant createdAt) {
            this(serverName, subscriptionId, resourceGroupName, location, administratorLogin,
                administratorLoginPassword, containerId, hostPort, host, provisioningState, failureCode,
                failureMessage, tags, databases, firewallRules, createdAt, SqlServerAccess.DEFAULT,
                new ConcurrentHashMap<>());
        }

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
                tags, databases, firewallRules, createdAt, access, settings);
        }

        /** This server's own fields over the databases, firewall rules and settings of {@code stored}. */
        public SqlServerEntry withChildrenOf(SqlServerEntry stored) {
            return new SqlServerEntry(serverName, subscriptionId, resourceGroupName,
                location, administratorLogin, administratorLoginPassword,
                containerId, hostPort, host, provisioningState, failureCode, failureMessage,
                tags, stored.databases(), stored.firewallRules(), createdAt, access, stored.settings());
        }

        public SqlServerEntry withProvisioningState(String newState, String code, String message) {
            return new SqlServerEntry(serverName, subscriptionId, resourceGroupName,
                location, administratorLogin, administratorLoginPassword,
                containerId, hostPort, host, newState, code, message,
                tags, databases, firewallRules, createdAt, access, settings);
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

    /**
     * The network settings of a server's PUT body, echoed back on every read.
     *
     * <p>{@code minimalTlsVersion} is {@code None} when a create names none: azurerm reads a missing value and
     * {@code None} alike, as "Disabled" ({@code mssql_server_resource.go}, which cites Azure/azure-rest-api-specs#24348
     * for Azure answering {@code None}). {@code publicNetworkAccess} is {@code Enabled} and
     * {@code restrictOutboundNetworkAccess} {@code Disabled} unless the request says otherwise, the values azurerm
     * sends for its defaults.
     */
    @RegisterForReflection
    public record SqlServerAccess(String minimalTlsVersion, String publicNetworkAccess,
                                  String restrictOutboundNetworkAccess) {

        public static final SqlServerAccess DEFAULT = new SqlServerAccess("None", "Enabled", "Disabled");

        /** Each field of {@code this} that is null replaced by the one of {@code fallback}. */
        public SqlServerAccess orElse(SqlServerAccess fallback) {
            return new SqlServerAccess(
                minimalTlsVersion != null ? minimalTlsVersion : fallback.minimalTlsVersion(),
                publicNetworkAccess != null ? publicNetworkAccess : fallback.publicNetworkAccess(),
                restrictOutboundNetworkAccess != null ? restrictOutboundNetworkAccess
                    : fallback.restrictOutboundNetworkAccess());
        }
    }

    /**
     * Represents a SQL Database inside a logical server.
     *
     * <p>{@code maxSizeBytes} is 0 for a database stored before it was kept; it reads as the default.
     */
    @RegisterForReflection
    public record SqlDatabaseEntry(
            String databaseName,
            String serverName,
            String collation,
            String edition,
            String sku,
            String status,
            String databaseId,
            Instant createdAt,
            long maxSizeBytes,
            String requestedBackupStorageRedundancy,
            boolean zoneRedundant,
            Map<String, String> tags,
            Map<String, SqlSetting> settings
    ) {
        /** 1 GiB: the size the spec's default-mode create example asks for and reads back. */
        public static final long DEFAULT_MAX_SIZE_BYTES = 1073741824L;

        /** The backup storage redundancy of a create that names none, as the spec's create example reads back. */
        public static final String DEFAULT_BACKUP_STORAGE_REDUNDANCY = "Geo";

        public static SqlDatabaseEntry create(String dbName, String serverName,
                                               String collation, String edition, String sku) {
            return create(dbName, serverName, new SqlDatabaseRequest(collation, edition, sku, null, null, null,
                Map.of()));
        }

        public static SqlDatabaseEntry create(String dbName, String serverName, SqlDatabaseRequest request) {
            return new SqlDatabaseEntry(
                dbName, serverName,
                nonBlank(request.collation(), "SQL_Latin1_General_CP1_CI_AS"),
                nonBlank(request.edition(), "Standard"),
                nonBlank(request.sku(), "S0"),
                "Online",
                UUID.randomUUID().toString(),
                Instant.now(),
                request.maxSizeBytes() != null ? request.maxSizeBytes() : DEFAULT_MAX_SIZE_BYTES,
                nonBlank(request.requestedBackupStorageRedundancy(), DEFAULT_BACKUP_STORAGE_REDUNDANCY),
                Boolean.TRUE.equals(request.zoneRedundant()),
                new ConcurrentHashMap<>(request.tags() != null ? request.tags() : Map.of()),
                new ConcurrentHashMap<>());
        }

        /** The master database is auto-created when a server is provisioned. */
        public static SqlDatabaseEntry master(String serverName) {
            return new SqlDatabaseEntry("master", serverName,
                "SQL_Latin1_General_CP1_CI_AS", "System", "System",
                "Online", UUID.randomUUID().toString(), Instant.now(), DEFAULT_MAX_SIZE_BYTES,
                DEFAULT_BACKUP_STORAGE_REDUNDANCY, false, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }

        /**
         * This database with the fields {@code request} names replaced; its id, creation date and settings are
         * kept. A PUT passes every field, so a field it leaves out takes its default; a PATCH keeps it.
         */
        public SqlDatabaseEntry updatedWith(SqlDatabaseRequest request, boolean replace) {
            return new SqlDatabaseEntry(databaseName, serverName,
                pick(request.collation(), replace ? "SQL_Latin1_General_CP1_CI_AS" : collation),
                pick(request.edition(), replace ? "Standard" : edition),
                pick(request.sku(), replace ? "S0" : sku),
                status, databaseId, createdAt,
                request.maxSizeBytes() != null ? request.maxSizeBytes()
                    : replace ? DEFAULT_MAX_SIZE_BYTES : effectiveMaxSizeBytes(),
                pick(request.requestedBackupStorageRedundancy(),
                    replace ? DEFAULT_BACKUP_STORAGE_REDUNDANCY : effectiveBackupStorageRedundancy()),
                request.zoneRedundant() != null ? request.zoneRedundant() : !replace && zoneRedundant,
                request.tags() != null ? new ConcurrentHashMap<>(request.tags())
                    : new ConcurrentHashMap<>(tags != null ? tags : Map.of()),
                settings != null ? settings : new ConcurrentHashMap<>());
        }

        /** This database with {@code setting} stored as its setting of {@code type}. */
        public SqlDatabaseEntry withSetting(SqlSettingType type, SqlSetting setting) {
            Map<String, SqlSetting> updated = new ConcurrentHashMap<>(settings != null ? settings : Map.of());
            updated.put(type.key(), setting);
            return new SqlDatabaseEntry(databaseName, serverName, collation, edition, sku, status, databaseId,
                createdAt, maxSizeBytes, requestedBackupStorageRedundancy, zoneRedundant, tags, updated);
        }

        public long effectiveMaxSizeBytes() {
            return maxSizeBytes > 0 ? maxSizeBytes : DEFAULT_MAX_SIZE_BYTES;
        }

        public String effectiveBackupStorageRedundancy() {
            return nonBlank(requestedBackupStorageRedundancy, DEFAULT_BACKUP_STORAGE_REDUNDANCY);
        }

        private static String pick(String requested, String otherwise) {
            return requested != null && !requested.isBlank() ? requested : otherwise;
        }

        private static String nonBlank(String value, String otherwise) {
            return pick(value, otherwise);
        }
    }

    /**
     * The database fields a PUT or PATCH body names; a field the body leaves out is null ({@code tags} included).
     */
    public record SqlDatabaseRequest(String collation, String edition, String sku, Long maxSizeBytes,
                                     String requestedBackupStorageRedundancy, Boolean zoneRedundant,
                                     Map<String, String> tags) {
    }

    /**
     * The properties of one singleton setting of a server or a database ({@link SqlSettingType}), as last written;
     * a setting never written is served with its type's defaults.
     */
    @RegisterForReflection
    public record SqlSetting(Map<String, Object> properties) {
    }

    /**
     * Represents a server-level firewall rule.
     * Rules are stored for API compliance but not enforced: all IPs are allowed in dev mode.
     */
    @RegisterForReflection
    public record SqlFirewallRule(
            String name,
            String startIpAddress,
            String endIpAddress
    ) {}
}
