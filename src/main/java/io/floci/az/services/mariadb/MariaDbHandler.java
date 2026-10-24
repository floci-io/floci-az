package io.floci.az.services.mariadb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.GlobalName;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.arm.GlobalNameSource;
import io.floci.az.core.arm.ResourceIndexContributor;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP handler for Azure Database for MariaDB management-plane requests
 * ({@code Microsoft.DBforMariaDB/servers}).
 *
 * <h2>Implemented operations</h2>
 * <ul>
 *   <li>Servers: create (PUT), get, list, update (PATCH), delete, checkNameAvailability</li>
 *   <li>Databases: create (PUT), get, list, delete</li>
 *   <li>Firewall rules: create, get, list, delete</li>
 *   <li>Configurations: get, list, put</li>
 *   <li>Convenience: {@code /connect} — returns all connection string formats</li>
 * </ul>
 *
 * <p>Azure Database for MariaDB uses the single-server model (resource type
 * {@code Microsoft.DBforMariaDB/servers}, not flexibleServers).
 */
@ApplicationScoped
public class MariaDbHandler implements AzureServiceHandler, Resettable, ResourceIndexContributor, GlobalNameSource {

    private static final Logger LOG = Logger.getLogger(MariaDbHandler.class);
    private static final String TYPE = "Microsoft.DBforMariaDB/servers";

    private static final String NAMESPACE = "Microsoft.DBforMariaDB";

    private final EmulatorConfig config;
    private final MariaDbState state;
    private final MariaDbServerManager serverManager;
    private final GlobalNameRegistry names;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Object> startLocks = new ConcurrentHashMap<>();

    /**
     * Held while a create claims a server and while a delete decides whether to free its name, so a delete cannot
     * free the name of a server created again at its id in between.
     */
    private final Object serverNames = new Object();

    @Inject
    public MariaDbHandler(EmulatorConfig config, MariaDbState state, MariaDbServerManager serverManager,
                          GlobalNameRegistry names) {
        this.config = config;
        this.state = state;
        this.serverManager = serverManager;
        this.names = names;
    }

    @Override public String getServiceType()           { return "mariadb"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().mariaDb().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .account("-mariadb", "mariadb")
                .provider("Microsoft.DBforMariaDB")
                .build();
    }

    @Override
    public Response handle(AzureRequest request) {
        String tail = extractMariaDbPath(request.resourcePath());
        String method = request.method();

        LOG.debugf("MariaDbHandler: %s %s → tail=%s", method, request.resourcePath(), tail);

        if (tail.endsWith("checkNameAvailability") && "POST".equals(method)) {
            return handleCheckNameAvailability(request);
        }

        if (tail.matches("servers/[^/]+/connect")) {
            return handleServerConnect(request, segment(tail, 1));
        }

        if (tail.matches("servers/[^/]+/configurations/[^/]+")) {
            return handleConfiguration(method, request, segment(tail, 1), segment(tail, 3));
        }
        if (tail.matches("servers/[^/]+/configurations")) {
            return handleConfigurationList(request, segment(tail, 1));
        }

        if (tail.matches("servers/[^/]+/firewallRules/[^/]+")) {
            return handleFirewallRule(method, request, segment(tail, 1), segment(tail, 3));
        }
        if (tail.matches("servers/[^/]+/firewallRules")) {
            return handleFirewallRuleList(method, request, segment(tail, 1));
        }

        if (tail.matches("servers/[^/]+/databases/[^/]+")) {
            return handleDatabase(method, request, segment(tail, 1), segment(tail, 3));
        }
        if (tail.matches("servers/[^/]+/databases")) {
            return handleDatabaseList(request, segment(tail, 1));
        }

        if (tail.matches("servers/[^/]+")) {
            return handleServer(method, request, segment(tail, 1));
        }
        if ("servers".equalsIgnoreCase(tail) || tail.isEmpty()) {
            return handleServerList(request);
        }

        return Response.status(Response.Status.NOT_FOUND)
            .entity(Map.of("error", "Unknown MariaDB path: " + tail))
            .build();
    }

    // ── checkNameAvailability ─────────────────────────────────────────────────

    /** Whether the name is owned, answered from the {@link GlobalNameRegistry} in the shape it always had. */
    private Response handleCheckNameAvailability(AzureRequest request) {
        try {
            JsonNode body = readBody(request.bodyStream());
            String name = body.path("name").asText();
            boolean available = names.owner(cloud(request), CloudSuffix.MARIADB, name).isEmpty();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("nameAvailable", available);
            resp.put("name", name);
            resp.put("reason", available ? null : "AlreadyExists");
            resp.put("message", available ? null : "Server name '" + name + "' is already taken.");
            return Response.ok(resp).build();
        } catch (Exception e) {
            return badRequest("Invalid request body: " + e.getMessage());
        }
    }

    // ── Servers ───────────────────────────────────────────────────────────────

    private Response handleServer(String method, AzureRequest request, String serverName) {
        return switch (method) {
            case "PUT"    -> createOrUpdateServer(request, serverName, false);
            case "PATCH"  -> createOrUpdateServer(request, serverName, true);
            case "GET"    -> getServer(request, serverName);
            case "DELETE" -> deleteServer(request, serverName);
            default       -> methodNotAllowed();
        };
    }

    private Response createOrUpdateServer(AzureRequest request, String serverName, boolean isPatch) {
        if (!config.services().mariaDb().enabled()) return serviceDisabled();

        try {
            JsonNode body  = readBody(request.bodyStream());
            JsonNode props = body.path("properties");
            Map<String, String> tags = parseTags(body.path("tags"));
            String sub = extractSubscriptionId(request.resourcePath());
            String rg  = extractResourceGroup(request.resourcePath());

            // A name another resource owns never reaches here through an ARM path: the gate's GlobalNameRule
            // answers the conflict before this runs.
            boolean isNew = server(request, serverName).isEmpty();

            if (isPatch && isNew) {
                return notFound("Server '" + serverName + "' not found");
            }

            if (isNew) {
                String login    = props.path("administratorLogin").asText();
                String password = props.path("administratorLoginPassword").asText();
                if (login.isBlank())    return badRequest("administratorLogin is required");
                if (password.isBlank()) return badRequest("administratorLoginPassword is required");

                String location = body.path("location").asText("eastus");
                String version  = props.path("version").asText("10.3");
                int storageMB   = props.path("storageProfile").path("storageMB").asInt(5120);

                MariaDbState.ServerEntry entry = new MariaDbState.ServerEntry(
                    serverName, sub, rg, location, version, login, password,
                    storageMB,
                    null, 0, "localhost", tags,
                    new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                    Instant.now());
                // The gate reserved the name already for an ARM create (this finds it OWNED); a create outside the
                // gate reserves it here, so an ARM create in flight and this one cannot both claim it.
                GlobalNameRegistry.Reservation reservation = names.reserve(cloud(request), CloudSuffix.MARIADB,
                    serverName, MariaDbState.serverId(entry));
                if (reservation.outcome() == GlobalNameRegistry.Outcome.TAKEN) {
                    return nameTaken(request, serverName);
                }
                try {
                    Optional<Response> createdResponse = createServer(request, entry);
                    if (createdResponse.isPresent()) {
                        return createdResponse.get();
                    }
                } finally {
                    // A no-op once the create committed the name, and for a reservation this request did not make.
                    names.releasePending(cloud(request), CloudSuffix.MARIADB, serverName, reservation.token());
                }
                // The name was claimed between the lookup and this write. A concurrent create of this server
                // takes this request as an update; a server of this name elsewhere is a conflict.
                if (server(request, serverName).isEmpty()) {
                    return nameTaken(request, serverName);
                }
            }

            String password = props.path("administratorLoginPassword").asText("");

            // Rotate-then-commit runs under the start lock so a concurrent /connect or GET
            // cannot start a container with the pre-update password between the rotation
            // decision and the state commit. Rotation happens BEFORE the commit: if ALTER USER
            // fails, state keeps the password the container still accepts. A server without a
            // container skips rotation — ensureStarted below initializes the fresh container
            // with the committed password.
            MariaDbState.ServerEntry updated;
            Object lock = startLocks.computeIfAbsent(serverName.toLowerCase(), k -> new Object());
            synchronized (lock) {
                // Everything below reads the entry as it is now. The wait for the lock can outlast
                // a delete and another scope's claim of the name, which this scope no longer reaches,
                // and an earlier queued update may have changed the fields this request leaves out.
                Optional<MariaDbState.ServerEntry> current = server(request, serverName);
                if (current.isEmpty()) {
                    return notFound("Server '" + serverName + "' not found");
                }
                MariaDbState.ServerEntry live = current.get();
                String version  = props.path("version").asText(live.version());
                int storageMB   = props.path("storageProfile").path("storageMB").asInt(live.storageMB());
                Map<String, String> mergedTags = body.has("tags") ? tags : live.tags();

                boolean passwordChanged = !password.isBlank()
                    && !password.equals(live.administratorLoginPassword());
                if (passwordChanged && !config.services().mariaDb().mocked() && live.containerId() != null) {
                    serverManager.rotateAdminPassword(live, password);
                }
                updated = new MariaDbState.ServerEntry(
                    live.serverName(), live.subscriptionId(), live.resourceGroupName(),
                    live.location(), version, live.administratorLogin(),
                    password.isBlank() ? live.administratorLoginPassword() : password,
                    storageMB,
                    live.containerId(), live.hostPort(), live.host(), mergedTags,
                    live.databases(), live.firewallRules(), live.configurations(),
                    live.createdAt());
                // Delete does not take the start lock, so the server can go away while the
                // rotation runs. Writing it back would resurrect a name another scope may claim.
                if (!state.replaceServer(live, updated)) {
                    return notFound("Server '" + serverName + "' not found");
                }
            }
            if (!config.services().mariaDb().mocked()) {
                updated = ensureStarted(updated);
            }
            return Response.status(200).entity(serverResponse(updated)).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error creating MariaDB server %s", serverName);
            return Response.status(500).entity(Map.of("error", String.valueOf(e.getMessage()))).build();
        }
    }

    /**
     * Rehydrate on read: clients (azurerm refresh, SDK pollers, list data sources) expect a
     * persisted server to be Ready after an emulator restart, not Creating. Best-effort — a
     * read must never fail because the container could not come back; the entry then reports
     * its current state instead.
     */
    private MariaDbState.ServerEntry rehydrateBestEffort(MariaDbState.ServerEntry entry) {
        if (config.services().mariaDb().mocked() || entry.containerId() != null) {
            return entry;
        }
        try {
            return ensureStarted(entry);
        } catch (Exception e) {
            LOG.warnf(e, "Rehydrate failed for MariaDB server %s — reporting current state",
                entry.serverName());
            return entry;
        }
    }

    private Response getServer(AzureRequest request, String serverName) {
        return server(request, serverName)
            .map(s -> Response.ok(serverResponse(rehydrateBestEffort(s))).build())
            .orElse(notFound("Server '" + serverName + "' not found"));
    }

    private Response deleteServer(AzureRequest request, String serverName) {
        Optional<MariaDbState.ServerEntry> entry = server(request, serverName);
        if (entry.isEmpty()) return notFound("Server '" + serverName + "' not found");
        state.removeServer(MariaDbState.serverId(entry.get()));
        releaseNameIfGone(cloud(request), entry.get());
        try { serverManager.stopServer(entry.get()); } catch (Exception e) {
            LOG.warnf(e, "Error stopping MariaDB container for server %s", serverName);
        }
        return Response.status(204).build();
    }

    /**
     * Claims {@code claimed} and starts its container: the created answer, or the container failure. Empty when
     * another create stored the name first.
     */
    private Optional<Response> createServer(AzureRequest request, MariaDbState.ServerEntry claimed) {
        String serverName = claimed.serverName();
        synchronized (serverNames) {
            if (!state.claimServer(claimed)) {
                return Optional.empty();
            }
        }
        if (config.services().mariaDb().mocked()) {
            return Optional.of(created(request, claimed));
        }

        MariaDbState.ServerEntry entry;
        try {
            Object lock = startLocks.computeIfAbsent(serverName.toLowerCase(), k -> new Object());
            synchronized (lock) {
                Optional<MariaDbState.ServerEntry> current = state.getServer(MariaDbState.serverId(claimed));
                if (current.isPresent() && current.get().containerId() != null) {
                    entry = current.get();
                } else {
                    entry = serverManager.startServer(claimed);
                    Optional<MariaDbState.ServerEntry> attached = state.attachContainer(entry);
                    if (attached.isPresent()) {
                        entry = attached.get();
                    } else {
                        // Deleted while its container started: writing it back would resurrect
                        // a name another subscription may have claimed since.
                        stopQuietly(entry);
                    }
                }
            }
        } catch (Exception e) {
            state.releaseClaim(claimed);
            LOG.errorf(e, "Failed to start MariaDB container for server=%s", serverName);
            return Optional.of(Response.status(500)
                .entity(Map.of("error", "ContainerStartFailed", "message", String.valueOf(e.getMessage())))
                .build());
        }
        return Optional.of(created(request, entry));
    }

    /**
     * Frees the name of a deleted server, unless a create stored a server at its id again since: that create's
     * commit holds the name now. Creates claim under the same lock, so none lands between the check and the release.
     */
    void releaseNameIfGone(CloudProfile cloud, MariaDbState.ServerEntry deleted) {
        ArmResourceId id = MariaDbState.serverId(deleted);
        synchronized (serverNames) {
            if (state.getServer(id).isEmpty()) {
                names.release(cloud, CloudSuffix.MARIADB, deleted.serverName(), id);
            }
        }
    }

    private Response handleServerList(AzureRequest request) {
        String sub = extractSubscriptionId(request.resourcePath());
        // A path with no resourceGroups segment lists the subscription; a group named "default" is a group.
        Optional<String> rg = request.resourceGroup();
        List<MariaDbState.ServerEntry> servers = rg.isEmpty()
            ? state.listServersBySubscription(sub)
            : state.listServersByResourceGroup(sub, rg.get());
        List<Map<String, Object>> value = servers.stream()
            .map(this::rehydrateBestEffort)
            .map(this::serverResponse)
            .toList();
        return Response.ok(Map.of("value", value)).build();
    }

    // ── Databases ─────────────────────────────────────────────────────────────

    private Response handleDatabase(String method, AzureRequest request,
                                    String serverName, String dbName) {
        return switch (method) {
            case "PUT"    -> createOrUpdateDatabase(request, serverName, dbName);
            case "GET"    -> getDatabase(request, serverName, dbName);
            case "DELETE" -> deleteDatabase(request, serverName, dbName);
            default       -> methodNotAllowed();
        };
    }

    private Response createOrUpdateDatabase(AzureRequest request, String serverName, String dbName) {
        Optional<MariaDbState.ServerEntry> serverOpt = server(request, serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");

        try {
            JsonNode body  = readBody(request.bodyStream());
            JsonNode props = body.path("properties");
            String charset   = props.path("charset").asText("");
            String collation = props.path("collation").asText("");

            boolean isNew = !state.databaseExists(serverName, dbName);
            MariaDbState.DatabaseEntry db = MariaDbState.DatabaseEntry.create(
                dbName, serverName, charset, collation);
            state.putDatabase(serverName, db);

            return Response.status(isNew ? 201 : 200)
                .entity(databaseResponse(db, serverOpt.get()))
                .build();
        } catch (Exception e) {
            LOG.errorf(e, "Error creating database %s on server %s", dbName, serverName);
            return Response.status(500).entity(Map.of("error", String.valueOf(e.getMessage()))).build();
        }
    }

    private Response getDatabase(AzureRequest request, String serverName, String dbName) {
        Optional<MariaDbState.ServerEntry> serverOpt = server(request, serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");
        return state.getDatabase(serverName, dbName)
            .map(db -> Response.ok(databaseResponse(db, serverOpt.get())).build())
            .orElse(notFound("Database '" + dbName + "' not found on server '" + serverName + "'"));
    }

    private Response deleteDatabase(AzureRequest request, String serverName, String dbName) {
        Optional<MariaDbState.ServerEntry> serverOpt = server(request, serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");
        if (!state.databaseExists(serverName, dbName))
            return notFound("Database '" + dbName + "' not found");
        state.removeDatabase(serverName, dbName);
        return Response.status(204).build();
    }

    private Response handleDatabaseList(AzureRequest request, String serverName) {
        Optional<MariaDbState.ServerEntry> serverOpt = server(request, serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");
        List<Map<String, Object>> value = state.listDatabases(serverName).stream()
            .map(db -> databaseResponse(db, serverOpt.get()))
            .toList();
        return Response.ok(Map.of("value", value)).build();
    }

    // ── Firewall rules ────────────────────────────────────────────────────────

    private Response handleFirewallRule(String method, AzureRequest request,
                                        String serverName, String ruleName) {
        if (server(request, serverName).isEmpty())
            return notFound("Server '" + serverName + "' not found");
        return switch (method) {
            case "PUT"    -> createFirewallRule(request, serverName, ruleName);
            case "GET"    -> state.getFirewallRule(serverName, ruleName)
                                  .map(r -> Response.ok(firewallRuleResponse(r, serverName)).build())
                                  .orElse(notFound("Firewall rule '" + ruleName + "' not found"));
            case "DELETE" -> {
                if (!state.getFirewallRule(serverName, ruleName).isPresent())
                    yield notFound("Firewall rule '" + ruleName + "' not found");
                state.removeFirewallRule(serverName, ruleName);
                yield Response.status(204).build();
            }
            default -> methodNotAllowed();
        };
    }

    private Response createFirewallRule(AzureRequest request, String serverName, String ruleName) {
        try {
            JsonNode body  = readBody(request.bodyStream());
            JsonNode props = body.path("properties");
            String start   = props.path("startIpAddress").asText();
            String end     = props.path("endIpAddress").asText();
            if (start.isBlank() || end.isBlank())
                return badRequest("startIpAddress and endIpAddress are required");
            boolean isNew = !state.getFirewallRule(serverName, ruleName).isPresent();
            MariaDbState.FirewallRule rule = new MariaDbState.FirewallRule(ruleName, start, end);
            state.putFirewallRule(serverName, rule);
            return Response.status(isNew ? 201 : 200)
                .entity(firewallRuleResponse(rule, serverName)).build();
        } catch (Exception e) {
            return badRequest("Invalid firewall rule body: " + e.getMessage());
        }
    }

    private Response handleFirewallRuleList(String method, AzureRequest request, String serverName) {
        if (server(request, serverName).isEmpty())
            return notFound("Server '" + serverName + "' not found");
        if ("GET".equals(method)) {
            List<Map<String, Object>> value = state.listFirewallRules(serverName).stream()
                .map(r -> firewallRuleResponse(r, serverName))
                .toList();
            return Response.ok(Map.of("value", value)).build();
        }
        return methodNotAllowed();
    }

    // ── Configurations ──────────────────────────────────────────────────────────

    private Response handleConfiguration(String method, AzureRequest request,
                                         String serverName, String cfgName) {
        if (server(request, serverName).isEmpty())
            return notFound("Server '" + serverName + "' not found");
        return switch (method) {
            case "PUT", "PATCH" -> {
                try {
                    JsonNode body = readBody(request.bodyStream());
                    String value  = body.path("properties").path("value").asText("");
                    state.putConfiguration(serverName, cfgName, value);
                    yield Response.status(200).entity(configurationResponse(serverName, cfgName, value)).build();
                } catch (Exception e) {
                    yield badRequest("Invalid configuration body: " + e.getMessage());
                }
            }
            case "GET" -> {
                String value = state.getConfiguration(serverName, cfgName).orElse("");
                yield Response.ok(configurationResponse(serverName, cfgName, value)).build();
            }
            default -> methodNotAllowed();
        };
    }

    private Response handleConfigurationList(AzureRequest request, String serverName) {
        if (server(request, serverName).isEmpty())
            return notFound("Server '" + serverName + "' not found");
        List<Map<String, Object>> value = state.listConfigurations(serverName).entrySet().stream()
            .map(e -> configurationResponse(serverName, e.getKey(), e.getValue()))
            .toList();
        return Response.ok(Map.of("value", value)).build();
    }

    // ── Convenience /connect ──────────────────────────────────────────────────

    /**
     * Restarts the sidecar for a server whose container is gone — after an emulator restart,
     * {@code loadFromStore} rebuilds persisted entries with {@code containerId=null}. Servers
     * with a live container pass through untouched. Callers skip this in mocked mode.
     */
    private MariaDbState.ServerEntry ensureStarted(MariaDbState.ServerEntry entry) {
        if (entry.containerId() != null) {
            return entry;
        }
        Object lock = startLocks.computeIfAbsent(entry.serverName().toLowerCase(), k -> new Object());
        synchronized (lock) {
            Optional<MariaDbState.ServerEntry> found = state.getServer(MariaDbState.serverId(entry));
            if (found.isEmpty()) {
                // Deleted since the caller read it: starting a container would bring it back.
                return entry;
            }
            MariaDbState.ServerEntry current = found.get();
            if (current.containerId() != null) {
                return current;
            }
            MariaDbState.ServerEntry started = serverManager.startServer(current);
            Optional<MariaDbState.ServerEntry> attached = state.attachContainer(started);
            if (attached.isEmpty()) {
                // Deleted while its container started, which delete does not wait for.
                stopQuietly(started);
                return entry;
            }
            return attached.get();
        }
    }

    private Response handleServerConnect(AzureRequest request, String serverName) {
        Optional<MariaDbState.ServerEntry> found = server(request, serverName);
        if (found.isEmpty()) {
            return notFound("Server '" + serverName + "' not found");
        }
        MariaDbState.ServerEntry entry = found.get();
        if (!config.services().mariaDb().mocked()) {
            try {
                entry = ensureStarted(entry);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to restart MariaDB container for server=%s", serverName);
                return Response.status(500)
                    .entity(Map.of("error", "ContainerStartFailed", "message", String.valueOf(e.getMessage())))
                    .build();
            }
        }
        MariaDbConnectionInfo info = MariaDbConnectionInfo.of(
            entry.fullyQualifiedDomainName(), entry.hostPort(),
            entry.administratorLogin(), entry.administratorLoginPassword(), null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("server", entry.serverName());
        resp.put("host", info.host());
        resp.put("port", info.port());
        resp.put("jdbcUrl", info.jdbcUrl());
        resp.put("uri", info.uri());
        resp.put("mysql", info.mysql());
        resp.put("dotNet", info.dotNet());
        return Response.ok(resp).build();
    }

    // ── Response builders ─────────────────────────────────────────────────────

    private Map<String, Object> serverResponse(MariaDbState.ServerEntry s) {
        boolean ready = config.services().mariaDb().mocked() || s.containerId() != null;

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("administratorLogin", s.administratorLogin());
        props.put("version", s.version());
        props.put("fullyQualifiedDomainName", s.fullyQualifiedDomainName());
        props.put("userVisibleState", ready ? "Ready" : "Disabled");
        props.put("provisioningState", ready ? "Succeeded" : "Creating");
        props.put("storageProfile", Map.of("storageMB", s.storageMB()));
        props.put("sslEnforcement", "Disabled");
        if (s.hostPort() > 0) props.put("localPort", s.hostPort());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", s.armId());
        resp.put("name", s.serverName());
        resp.put("type", TYPE);
        resp.put("location", s.location());
        if (!s.tags().isEmpty()) resp.put("tags", s.tags());
        resp.put("properties", props);
        return resp;
    }

    private Map<String, Object> databaseResponse(MariaDbState.DatabaseEntry db,
                                                  MariaDbState.ServerEntry server) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", server.armId() + "/databases/" + db.databaseName());
        resp.put("name", db.databaseName());
        resp.put("type", "Microsoft.DBforMariaDB/servers/databases");
        resp.put("properties", Map.of(
            "charset", db.charset(),
            "collation", db.collation()));
        return resp;
    }

    private Map<String, Object> firewallRuleResponse(MariaDbState.FirewallRule rule, String serverName) {
        Optional<MariaDbState.ServerEntry> s = state.getServer(serverName);
        String armId = s.map(e -> e.armId() + "/firewallRules/" + rule.name())
            .orElse("/providers/Microsoft.DBforMariaDB/servers/" + serverName
                + "/firewallRules/" + rule.name());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", armId);
        resp.put("name", rule.name());
        resp.put("type", "Microsoft.DBforMariaDB/servers/firewallRules");
        resp.put("properties", Map.of(
            "startIpAddress", rule.startIpAddress(),
            "endIpAddress", rule.endIpAddress()));
        return resp;
    }

    private Map<String, Object> configurationResponse(String serverName, String cfgName, String value) {
        Optional<MariaDbState.ServerEntry> s = state.getServer(serverName);
        String armId = s.map(e -> e.armId() + "/configurations/" + cfgName)
            .orElse("/providers/Microsoft.DBforMariaDB/servers/" + serverName
                + "/configurations/" + cfgName);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", armId);
        resp.put("name", cfgName);
        resp.put("type", "Microsoft.DBforMariaDB/servers/configurations");
        resp.put("properties", Map.of(
            "value", value,
            "source", "user-override"));
        return resp;
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private static String extractMariaDbPath(String fullPath) {
        if (fullPath == null) return "";
        return ArmPaths.afterProvider(fullPath, NAMESPACE, fullPath);
    }

    private static String extractSubscriptionId(String fullPath) {
        if (fullPath == null) return "default";
        String[] parts = fullPath.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("subscriptions".equalsIgnoreCase(parts[i])) return parts[i + 1];
        }
        return "default";
    }

    private static String extractResourceGroup(String fullPath) {
        if (fullPath == null) return "default";
        String[] parts = fullPath.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("resourcegroups".equalsIgnoreCase(parts[i])) return parts[i + 1];
        }
        return "default";
    }

    private static String segment(String path, int index) {
        String[] parts = path.split("/");
        return index < parts.length ? parts[index] : "";
    }

    private static Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }

    private JsonNode readBody(InputStream stream) {
        try {
            if (stream == null || stream.available() == 0) return mapper.createObjectNode();
            return mapper.readTree(stream);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    // ── Standard error responses ──────────────────────────────────────────────


    private void stopQuietly(MariaDbState.ServerEntry entry) {
        try {
            serverManager.stopServer(entry);
        } catch (Exception e) {
            LOG.warnf(e, "Error stopping MariaDB container for server %s", entry.serverName());
        }
    }

    /**
     * The server a path addresses. An ARM path reaches the server stored under its own id, so a server of that name
     * in another subscription or resource group is not found; a subscription-level path names no resource group and
     * owns nothing ({@link ArmScope}). The account path ({@code /{account}-mariadb/...}) has no scope and finds the
     * server by its name alone, which is unique.
     */
    private Optional<MariaDbState.ServerEntry> server(AzureRequest request, String serverName) {
        Optional<ArmScope> scope = scope(request);
        if (scope.isEmpty()) {
            return state.getServer(serverName);
        }
        if (scope.get().resourceGroup() == null) {
            return Optional.empty();
        }
        return state.getServer(MariaDbState.serverId(scope.get().subscription(), scope.get().resourceGroup(),
            serverName));
    }

    /**
     * A create answered 2xx: the name is kept in the {@link GlobalNameRegistry}. The gate's {@code GlobalNameRule}
     * commits it too for an ARM create; this also covers the creates outside the gate (the account path, and a
     * path with no resource group), so the registry knows every stored server.
     */
    private Response created(AzureRequest request, MariaDbState.ServerEntry entry) {
        names.commit(cloud(request), CloudSuffix.MARIADB, entry.serverName(), MariaDbState.serverId(entry));
        return Response.status(201).entity(serverResponse(entry)).build();
    }

    /**
     * A create that found the name held by a server this path cannot reach: the type's own conflict for a create in
     * a resource group, and a 404 for a path that names none, which creates nothing of its own.
     */
    private static Response nameTaken(AzureRequest request, String serverName) {
        if (scope(request).filter(armScope -> armScope.resourceGroup() == null).isPresent()) {
            return notFound("Server '" + serverName + "' not found");
        }
        GlobalName global = MariaDbArmSurface.SERVER_DNS_NAME;
        return ArmErrors.error(global.conflictStatus(), global.conflictCode(),
            String.format(global.conflictMessage(), serverName));
    }

    /** The subscription and resource group of an ARM path; empty for the account path. */
    private static Optional<ArmScope> scope(AzureRequest request) {
        return ArmScope.of(request.resourcePath());
    }

    private static CloudProfile cloud(AzureRequest request) {
        return request.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD)
            : request.scope().cloud();
    }

    private static Response notFound(String message) {
        return Response.status(404).entity(Map.of(
            "error", Map.of("code", "ResourceNotFound", "message", message))).build();
    }

    private static Response badRequest(String message) {
        return Response.status(400).entity(Map.of(
            "error", Map.of("code", "InvalidRequest", "message", message))).build();
    }

    private static Response methodNotAllowed() {
        return Response.status(405).entity(Map.of("error", "Method not allowed")).build();
    }

    private static Response serviceDisabled() {
        return Response.status(503).entity(Map.of(
            "error", Map.of("code", "ServiceDisabled",
                "message", "Azure Database for MariaDB service is disabled on this emulator."))).build();
    }

    public void clear() {
        state.listServers().forEach(entry -> {
            try { serverManager.stopServer(entry); } catch (Exception e) {
                LOG.warnf(e, "Error stopping MariaDB container during reset: server=%s", entry.serverName());
            }
        });
        state.clear();
        startLocks.clear();
    }

    // ── ResourceIndexContributor ────────────────────────────────────────────────

    @Override
    public boolean indexEnabled() {
        return config.services().mariaDb().enabled();
    }

    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return indexEntries(state.listServersByResourceGroup(sub, rg));
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return indexEntries(state.listServersBySubscription(sub));
    }

    private static List<Map<String, Object>> indexEntries(List<MariaDbState.ServerEntry> servers) {
        return servers.stream()
                .map(server -> ArmResources.indexEntry(server.armId(), server.serverName(), TYPE,
                        server.location(), server.tags()))
                .toList();
    }

    // ── GlobalNameSource ────────────────────────────────────────────────────────

    @Override
    public List<Claim> globalNames() {
        return state.listServers().stream()
                .map(server -> new Claim(CloudSuffix.MARIADB, server.serverName(), MariaDbState.serverId(server)))
                .toList();
    }

    @Override
    public boolean holds(CloudSuffix suffix, String name, ArmResourceId owner) {
        return suffix == CloudSuffix.MARIADB
            && state.getServer(owner).filter(server -> name.equalsIgnoreCase(server.serverName())).isPresent();
    }
}
