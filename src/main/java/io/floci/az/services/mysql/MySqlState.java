package io.floci.az.services.mysql;

import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State store for the Azure Database for MySQL (Flexible Server) emulator.
 *
 * <p>Tracks logical flexible servers and their child resources (databases, firewall rules, server configurations),
 * which live inside their server's record. Servers are kept in an {@link ArmResourceStore} over the {@code mysql}
 * backend, keyed by canonical resource id, so persistence follows {@code FLOCI_AZ_STORAGE_SERVICES_MYSQL_MODE}.
 * Before the store, servers were kept under their lowercased name alone; those entries are still read and move to
 * their canonical key on their next write.
 *
 * <p>A server name is a global DNS name, so the state holds one server per name, and a lookup by name alone (the
 * account path, {@code /{account}-mysql/...}) finds at most one.
 *
 * <p>Container runtime state ({@code containerId}, {@code hostPort}, {@code host}) is never persisted: it is kept in
 * memory beside the store, so after a restart a server reads back without a container, which restarts on first use.
 *
 * <p>Mirrors {@link io.floci.az.services.postgres.PostgresState}; all public methods are thread-safe via
 * {@code synchronized}.
 */
@ApplicationScoped
public class MySqlState {

    private static final Logger LOG = Logger.getLogger(MySqlState.class);

    private static final String TYPE = "Microsoft.DBforMySQL/flexibleServers";

    /** A server's container, as {@link ServerEntry} carries it at runtime. */
    private record Runtime(String containerId, int hostPort, String host) {
        static final Runtime NONE = new Runtime(null, 0, "localhost");
    }

    /** Every server, keyed by canonical id; legacy entries are keyed by lowercased name (no {@code /}). */
    private final ArmResourceStore<ServerEntry> store;

    /** The container of each server started in this run, by canonical id. */
    private final Map<String, Runtime> runtime = new ConcurrentHashMap<>();

    @Inject
    public MySqlState(StorageFactory factory) {
        this.store = ArmResourceStore.create(factory, "mysql", ServerEntry.class, ServerEntry::armId,
            key -> !key.contains("/"));
        int restored = store.all().size();
        if (restored > 0) {
            LOG.infov("Restored {0} MySQL server(s) from storage (containers will restart on next request)",
                restored);
        }
    }

    /** The id of the server {@code serverName} in {@code sub}/{@code rg}. */
    public static ArmResourceId serverId(String sub, String rg, String serverName) {
        return ArmResourceId.parse("/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/" + TYPE + "/"
            + serverName).orElseThrow();
    }

    /** The id of {@code entry}. */
    public static ArmResourceId serverId(ServerEntry entry) {
        return ArmResourceId.parse(entry.armId()).orElseThrow();
    }

    // ── Servers ───────────────────────────────────────────────────────────────

    public synchronized boolean serverExists(String serverName) {
        return getServer(serverName).isPresent();
    }

    public synchronized void putServer(ServerEntry entry) {
        write(entry);
    }

    /**
     * Stores a new server unless its name is already taken, in any scope; false when a concurrent create claimed it
     * first.
     */
    public synchronized boolean claimServer(ServerEntry entry) {
        if (getServer(entry.serverName()).isPresent()) {
            return false;
        }
        write(entry);
        return true;
    }

    /**
     * Records a started container on the stored server, while that server is still the one {@code started}
     * was built from. Only the container fields are taken from {@code started}, so an update made while the
     * container started is kept. Empty once that server was deleted, or deleted and claimed again.
     */
    public synchronized Optional<ServerEntry> attachContainer(ServerEntry started) {
        Optional<ServerEntry> current = getServer(serverId(started));
        if (current.isEmpty() || !current.get().createdAt().equals(started.createdAt())) {
            return Optional.empty();
        }
        ServerEntry attached = current.get().withContainer(started.containerId(), started.hostPort(), started.host());
        write(attached);
        return Optional.of(attached);
    }

    /**
     * Stores {@code updated} while the stored server is still the one {@code expected} was read from. False
     * once that server was deleted, or deleted and claimed again, so an update never writes a server back.
     */
    public synchronized boolean replaceServer(ServerEntry expected, ServerEntry updated) {
        Optional<ServerEntry> current = getServer(serverId(expected));
        if (current.isEmpty() || !current.get().createdAt().equals(expected.createdAt())) {
            return false;
        }
        write(updated);
        return true;
    }

    /**
     * Removes a server this request claimed, but only while the stored entry is still that claim. A failed
     * create must not remove a server another subscription claimed after this one was deleted.
     */
    public synchronized void releaseClaim(ServerEntry claimed) {
        ArmResourceId id = serverId(claimed);
        Optional<ServerEntry> current = getServer(id);
        if (current.isPresent() && current.get().createdAt().equals(claimed.createdAt())) {
            delete(id);
        }
    }

    /** The server of this name, in whichever scope owns it. */
    public synchronized Optional<ServerEntry> getServer(String serverName) {
        if (serverName == null) {
            return Optional.empty();
        }
        return store.all().stream()
            .map(ArmResourceStore.Stored::value)
            .filter(server -> serverName.equalsIgnoreCase(server.serverName()))
            .findFirst()
            .map(this::withRuntime);
    }

    /** The server {@code id} names; empty for a server of that name in another scope. */
    public synchronized Optional<ServerEntry> getServer(ArmResourceId id) {
        return store.get(id).map(stored -> withRuntime(stored.value()));
    }

    public synchronized boolean removeServer(String serverName) {
        return getServer(serverName).map(server -> delete(serverId(server))).orElse(false);
    }

    /** Removes the server {@code id} names; false when there is none. */
    public synchronized boolean removeServer(ArmResourceId id) {
        return delete(id);
    }

    public synchronized List<ServerEntry> listServers() {
        return store.all().stream().map(stored -> withRuntime(stored.value())).toList();
    }

    public synchronized List<ServerEntry> listServersBySubscription(String subscriptionId) {
        return list(new ArmScope(subscriptionId, null));
    }

    public synchronized List<ServerEntry> listServersByResourceGroup(String subscriptionId,
                                                                     String resourceGroup) {
        return list(new ArmScope(subscriptionId, resourceGroup));
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
            write(s);
        });
    }

    public synchronized Optional<DatabaseEntry> getDatabase(String serverName, String dbName) {
        return getServer(serverName)
            .map(s -> s.databases().get(key(dbName)));
    }

    public synchronized boolean removeDatabase(String serverName, String dbName) {
        return getServer(serverName).map(s -> {
            boolean removed = s.databases().remove(key(dbName)) != null;
            if (removed) {
                write(s);
            }
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
            write(s);
        });
    }

    public synchronized Optional<FirewallRule> getFirewallRule(String serverName, String ruleName) {
        return getServer(serverName)
            .map(s -> s.firewallRules().get(key(ruleName)));
    }

    public synchronized boolean removeFirewallRule(String serverName, String ruleName) {
        return getServer(serverName).map(s -> {
            boolean removed = s.firewallRules().remove(key(ruleName)) != null;
            if (removed) {
                write(s);
            }
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
            write(s);
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
     * Removes all servers (and their child resources) from the store and forgets their containers.
     * Callers are responsible for stopping running containers beforehand.
     */
    public synchronized void clear() {
        store.clear();
        runtime.clear();
    }

    // ── Store helpers ─────────────────────────────────────────────────────────

    private List<ServerEntry> list(ArmScope scope) {
        return store.list(scope).stream().map(stored -> withRuntime(stored.value())).toList();
    }

    /** Persists {@code entry} without its container, which is kept in memory only. */
    private void write(ServerEntry entry) {
        String key = serverId(entry).canonicalKey();
        runtime.put(key, new Runtime(entry.containerId(), entry.hostPort(), entry.host()));
        store.put(entry.withContainer(Runtime.NONE.containerId(), Runtime.NONE.hostPort(), Runtime.NONE.host()));
    }

    private boolean delete(ArmResourceId id) {
        runtime.remove(id.canonicalKey());
        return store.delete(id).isPresent();
    }

    /**
     * {@code stored} with this run's container, and its child maps mutable: a stored record, and one written before
     * the store, can carry a container from an earlier run, which never survives a restart.
     */
    private ServerEntry withRuntime(ServerEntry stored) {
        Runtime container = runtime.getOrDefault(serverId(stored).canonicalKey(), Runtime.NONE);
        return new ServerEntry(
            stored.serverName(), stored.subscriptionId(), stored.resourceGroupName(),
            stored.location(), stored.version(), stored.administratorLogin(), stored.administratorLoginPassword(),
            stored.skuName(), stored.skuTier(), stored.storageSizeGB(),
            container.containerId(), container.hostPort(), container.host(),
            new LinkedHashMap<>(stored.tags() != null ? stored.tags() : Map.of()),
            new ConcurrentHashMap<>(stored.databases() != null ? stored.databases() : Map.of()),
            new ConcurrentHashMap<>(stored.firewallRules() != null ? stored.firewallRules() : Map.of()),
            new ConcurrentHashMap<>(stored.configurations() != null ? stored.configurations() : Map.of()),
            stored.createdAt());
    }

    // ── Key helper ───────────────────────────────────────────────────────────

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
