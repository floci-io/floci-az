package io.floci.az.services.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.Resettable;
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
import io.floci.az.core.cloud.CloudDefaults;
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
 * HTTP handler for Azure Database for PostgreSQL (Flexible Server) management-plane requests
 * ({@code Microsoft.DBforPostgreSQL/flexibleServers}).
 *
 * <h2>Routing</h2>
 * <p>Two path styles are accepted:</p>
 * <ol>
 *   <li><b>ARM paths</b> (real Azure SDK / CLI / Terraform):
 *       {@code subscriptions/{sub}/[resourceGroups/{rg}/]providers/Microsoft.DBforPostgreSQL/…}</li>
 *   <li><b>Convenience paths</b> (floci-az style):
 *       {@code /{account}-postgres/…}</li>
 * </ol>
 *
 * <h2>Implemented operations</h2>
 * <ul>
 *   <li>Flexible servers: create (PUT), get, list, update (PATCH), delete, checkNameAvailability</li>
 *   <li>Databases: create (PUT), get, list, delete</li>
 *   <li>Firewall rules: create, get, list, delete</li>
 *   <li>Configurations: get, list, put</li>
 *   <li>Convenience: {@code /connect} — returns all connection string formats</li>
 * </ul>
 *
 * <p>Server create is fully synchronous: the container is started during the PUT and the
 * response body reports {@code provisioningState=Succeeded} / {@code state=Ready}.</p>
 *
 * <h2>Why mutating operations answer 202</h2>
 *
 * <p>Every mutating operation (PUT and PATCH on servers, databases, firewall rules and
 * configurations) returns <b>HTTP 202</b> with a {@code Location} header pointing at the
 * resource's own URL. ARM models these as long-running operations, and the provider's
 * expected status codes differ by major version:</p>
 *
 * <table border="1">
 *   <caption>go-azure-sdk expected status codes</caption>
 *   <tr><th>Operation</th><th>azurerm 3.x</th><th>azurerm 4.x</th></tr>
 *   <tr><td>PUT (create/update)</td><td>200, 201, 202</td><td>202 only</td></tr>
 *   <tr><td>PATCH (update)</td><td>200, 202</td><td>202 only</td></tr>
 *   <tr><td>DELETE</td><td>200, 202, 204</td><td>202, 204</td></tr>
 * </table>
 *
 * <p>202 is therefore the only value both majors accept; a 200 or 201 fails 4.x with
 * {@code unexpected status}. Because the work is already finished when we answer, the
 * {@code Location} header points back at the resource itself: the SDK's poller then reads
 * the terminal {@code provisioningState} (or, for the metadata-only children that carry no
 * such field, treats its absence as completion) and finishes on the first poll. DELETE keeps
 * returning 204, which both majors already accept.</p>
 */
@ApplicationScoped
public class PostgresHandler implements AzureServiceHandler, Resettable, ResourceIndexContributor, GlobalNameSource {

    private static final Logger LOG = Logger.getLogger(PostgresHandler.class);
    private static final String TYPE = "Microsoft.DBforPostgreSQL/flexibleServers";

    private static final String NAMESPACE = "Microsoft.DBforPostgreSQL";

    private final EmulatorConfig config;
    private final PostgresState state;
    private final PostgresServerManager serverManager;
    private final GlobalNameRegistry names;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Guards concurrent server-start operations (per server name). */
    private final ConcurrentHashMap<String, Object> startLocks = new ConcurrentHashMap<>();

    /**
     * Held while a create claims a server and while a delete decides whether to free its name, so a delete cannot
     * free the name of a server created again at its id in between.
     */
    private final Object serverNames = new Object();

    private final CloudDefaults defaults;

    @Inject
    public PostgresHandler(EmulatorConfig config, PostgresState state, PostgresServerManager serverManager,
                           GlobalNameRegistry names,
                           CloudDefaults defaults) {
        this.defaults = defaults;
        this.config = config;
        this.state = state;
        this.serverManager = serverManager;
        this.names = names;
    }

    @Override public String getServiceType()           { return "postgres"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().postgres().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .account("-postgres", "postgres")
                .provider("Microsoft.DBforPostgreSQL")
                .build();
    }

    @Override
    public Response handle(AzureRequest request) {
        String tail = extractPgPath(request.resourcePath());
        String method = request.method();

        LOG.debugf("PostgresHandler: %s %s → tail=%s", method, request.resourcePath(), tail);

        // ── checkNameAvailability ──────────────────────────────────────────
        // ARM: subscriptions/{sub}/providers/Microsoft.DBforPostgreSQL/locations/{loc}/checkNameAvailability
        if (tail.endsWith("checkNameAvailability") && "POST".equals(method)) {
            return handleCheckNameAvailability(request);
        }

        // ── Convenience /connect ───────────────────────────────────────────
        if (tail.matches("flexibleServers/[^/]+/connect")) {
            return handleServerConnect(request, segment(tail, 1));
        }

        // ── Configurations ─────────────────────────────────────────────────
        if (tail.matches("flexibleServers/[^/]+/configurations/[^/]+")) {
            return handleConfiguration(method, request, segment(tail, 1), segment(tail, 3));
        }
        if (tail.matches("flexibleServers/[^/]+/configurations")) {
            return handleConfigurationList(request, segment(tail, 1));
        }

        // ── Firewall rules ─────────────────────────────────────────────────
        if (tail.matches("flexibleServers/[^/]+/firewallRules/[^/]+")) {
            return handleFirewallRule(method, request, segment(tail, 1), segment(tail, 3));
        }
        if (tail.matches("flexibleServers/[^/]+/firewallRules")) {
            return handleFirewallRuleList(method, request, segment(tail, 1));
        }

        // ── Databases ──────────────────────────────────────────────────────
        if (tail.matches("flexibleServers/[^/]+/databases/[^/]+")) {
            return handleDatabase(method, request, segment(tail, 1), segment(tail, 3));
        }
        if (tail.matches("flexibleServers/[^/]+/databases")) {
            return handleDatabaseList(request, segment(tail, 1));
        }

        // ── Servers ────────────────────────────────────────────────────────
        if (tail.matches("flexibleServers/[^/]+")) {
            return handleServer(method, request, segment(tail, 1));
        }
        if ("flexibleServers".equalsIgnoreCase(tail) || tail.isEmpty()) {
            return handleServerList(request);
        }

        return Response.status(Response.Status.NOT_FOUND)
            .entity(Map.of("error", "Unknown PostgreSQL path: " + tail))
            .build();
    }

    // ── ARM long-running-operation responses ──────────────────────────────────

    /**
     * Builds the absolute URL of an ARM resource for the {@code Location} header,
     * preserving the caller's {@code api-version} so the polled URL matches the one
     * the client just wrote to.
     */
    private String resourceLocation(AzureRequest request, String armId) {
        String host = RequestUrls.resolveAuthority(request);
        if (host == null || host.isBlank()) host = "localhost:" + config.port();
        String scheme = request.headers().getHeaderString("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) scheme = request.secure() ? "https" : "http";
        String apiVersion = request.queryParams().get("api-version");
        return scheme + "://" + host + armId
            + (apiVersion == null || apiVersion.isBlank() ? "" : "?api-version=" + apiVersion);
    }

    /**
     * Completed mutating operation: 202 plus a self-referencing {@code Location}, the only
     * shape both azurerm majors accept (see class javadoc).
     */
    private Response accepted(AzureRequest request, String armId, Object body) {
        return Response.status(202)
            .header("Location", resourceLocation(request, armId))
            .entity(body)
            .build();
    }

    /**
     * A create answered 2xx: the name is kept in the {@link GlobalNameRegistry}. The gate's {@code GlobalNameRule}
     * commits it too for an ARM create; this also covers the creates outside the gate (the account path, and a
     * path with no resource group), so the registry knows every stored server.
     */
    private Response created(AzureRequest request, PostgresState.ServerEntry entry) {
        names.commit(cloud(request), CloudSuffix.POSTGRES, entry.serverName(), PostgresState.serverId(entry));
        return accepted(request, entry.armId(), serverResponse(entry));
    }

    // ── checkNameAvailability ─────────────────────────────────────────────────

    /** Whether the name is owned, answered from the {@link GlobalNameRegistry} in the shape it always had. */
    private Response handleCheckNameAvailability(AzureRequest request) {
        try {
            JsonNode body = readBody(request.bodyStream());
            String name = body.path("name").asText();
            boolean available = names.owner(cloud(request), CloudSuffix.POSTGRES, name).isEmpty();
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
        if (!config.services().postgres().enabled()) return serviceDisabled();

        try {
            JsonNode body  = readBody(request.bodyStream());
            JsonNode props = body.path("properties");
            JsonNode sku   = body.path("sku");
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

                String location = body.path("location").asText(defaults.defaultLocation(request.scope()));
                String version  = props.path("version").asText("16");
                int storageGB   = props.path("storage").path("storageSizeGB").asInt(32);
                String skuName  = sku.path("name").asText("Standard_B1ms");
                String skuTier  = sku.path("tier").asText("Burstable");

                PostgresState.ServerEntry entry = new PostgresState.ServerEntry(
                    serverName, sub, rg, location, version, login, password,
                    skuName, skuTier, storageGB,
                    null, 0, "localhost", tags,
                    new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                    Instant.now());
                // The gate reserved the name already for an ARM create (this finds it OWNED); a create outside the
                // gate reserves it here, so an ARM create in flight and this one cannot both claim it.
                GlobalNameRegistry.Reservation reservation = names.reserve(cloud(request), CloudSuffix.POSTGRES,
                    serverName, PostgresState.serverId(entry));
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
                    names.releasePending(cloud(request), CloudSuffix.POSTGRES, serverName, reservation.token());
                }
                // The name was claimed between the lookup and this write. A concurrent create of this server
                // takes this request as an update; a server of this name elsewhere is a conflict.
                if (server(request, serverName).isEmpty()) {
                    return nameTaken(request, serverName);
                }
            }

            // Update existing server metadata in place — do NOT restart the container.
            String password = props.path("administratorLoginPassword").asText("");

            // Rotate-then-commit under the start lock the create holds across startServer and
            // attachContainer: an update that lands while the container starts waits for it, then
            // rotates on the live container instead of committing a password the container never
            // got. Rotation runs before the commit, so a failed ALTER ROLE leaves state holding the
            // password the container still accepts. A server without a container (mocked, or not
            // started) only stores the new value.
            PostgresState.ServerEntry updated;
            Object lock = startLocks.computeIfAbsent(serverName.toLowerCase(), k -> new Object());
            synchronized (lock) {
                // Everything below reads the entry as it is now. The wait for the lock can outlast
                // a delete and another scope's claim of the name, which this scope no longer reaches,
                // and an earlier queued update may have changed the fields this request leaves out.
                Optional<PostgresState.ServerEntry> current = server(request, serverName);
                if (current.isEmpty()) {
                    return notFound("Server '" + serverName + "' not found");
                }
                PostgresState.ServerEntry live = current.get();
                String version  = props.path("version").asText(live.version());
                int storageGB   = props.path("storage").path("storageSizeGB").asInt(live.storageSizeGB());
                String skuName  = sku.path("name").asText(live.skuName());
                String skuTier  = sku.path("tier").asText(live.skuTier());
                Map<String, String> mergedTags = body.has("tags") ? tags : live.tags();

                boolean passwordChanged = !password.isBlank()
                    && !password.equals(live.administratorLoginPassword());
                if (passwordChanged && !config.services().postgres().mocked() && live.containerId() != null) {
                    serverManager.rotateAdminPassword(live, password);
                }
                updated = new PostgresState.ServerEntry(
                    live.serverName(), live.subscriptionId(), live.resourceGroupName(),
                    live.location(), version, live.administratorLogin(),
                    password.isBlank() ? live.administratorLoginPassword() : password,
                    skuName, skuTier, storageGB,
                    live.containerId(), live.hostPort(), live.host(), mergedTags,
                    live.databases(), live.firewallRules(), live.configurations(),
                    live.createdAt());
                // Delete does not take the start lock, so the server can go away while the
                // rotation runs. Writing it back would resurrect a name another scope may claim.
                if (!state.replaceServer(live, updated)) {
                    return notFound("Server '" + serverName + "' not found");
                }
            }
            return accepted(request, updated.armId(), serverResponse(updated));

        } catch (Exception e) {
            LOG.errorf(e, "Error creating PostgreSQL server %s", serverName);
            return Response.status(500).entity(Map.of("error", String.valueOf(e.getMessage()))).build();
        }
    }

    private Response getServer(AzureRequest request, String serverName) {
        return server(request, serverName)
            .map(s -> Response.ok(serverResponse(s)).build())
            .orElse(notFound("Server '" + serverName + "' not found"));
    }

    private Response deleteServer(AzureRequest request, String serverName) {
        Optional<PostgresState.ServerEntry> entry = server(request, serverName);
        if (entry.isEmpty()) return notFound("Server '" + serverName + "' not found");
        state.removeServer(PostgresState.serverId(entry.get()));
        releaseNameIfGone(cloud(request), entry.get());
        try { serverManager.stopServer(entry.get()); } catch (Exception e) {
            LOG.warnf(e, "Error stopping PostgreSQL container for server %s", serverName);
        }
        return Response.status(204).build();
    }

    /**
     * Claims {@code claimed} and starts its container: the created answer, or the container failure. Empty when
     * another create stored the name first.
     */
    private Optional<Response> createServer(AzureRequest request, PostgresState.ServerEntry claimed) {
        String serverName = claimed.serverName();
        synchronized (serverNames) {
            if (!state.claimServer(claimed)) {
                return Optional.empty();
            }
        }
        if (config.services().postgres().mocked()) {
            // Control-plane only: no container, data plane unavailable. Reports Ready.
            return Optional.of(created(request, claimed));
        }

        PostgresState.ServerEntry entry;
        try {
            Object lock = startLocks.computeIfAbsent(serverName.toLowerCase(), k -> new Object());
            synchronized (lock) {
                Optional<PostgresState.ServerEntry> current = state.getServer(PostgresState.serverId(claimed));
                if (current.isPresent() && current.get().containerId() != null) {
                    entry = current.get();
                } else {
                    entry = serverManager.startServer(claimed);
                    Optional<PostgresState.ServerEntry> attached = state.attachContainer(entry);
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
            LOG.errorf(e, "Failed to start PostgreSQL container for server=%s", serverName);
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
    void releaseNameIfGone(CloudProfile cloud, PostgresState.ServerEntry deleted) {
        ArmResourceId id = PostgresState.serverId(deleted);
        synchronized (serverNames) {
            if (state.getServer(id).isEmpty()) {
                names.release(cloud, CloudSuffix.POSTGRES, deleted.serverName(), id);
            }
        }
    }

    private Response handleServerList(AzureRequest request) {
        String sub = extractSubscriptionId(request.resourcePath());
        // A path with no resourceGroups segment lists the subscription; a group named "default" is a group.
        Optional<String> rg = request.resourceGroup();
        List<PostgresState.ServerEntry> servers = rg.isEmpty()
            ? state.listServersBySubscription(sub)
            : state.listServersByResourceGroup(sub, rg.get());
        List<Map<String, Object>> value = servers.stream().map(this::serverResponse).toList();
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
        Optional<PostgresState.ServerEntry> serverOpt = server(request, serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");

        try {
            JsonNode body  = readBody(request.bodyStream());
            JsonNode props = body.path("properties");
            String charset   = props.path("charset").asText("");
            String collation = props.path("collation").asText("");

            // The emulator tracks the database in state only. Actual CREATE DATABASE is the
            // application's responsibility (psql, Flyway, Liquibase, etc.) via the /connect URL.
            PostgresState.DatabaseEntry db = PostgresState.DatabaseEntry.create(
                dbName, serverName, charset, collation);
            state.putDatabase(serverName, db);

            Map<String, Object> resp = databaseResponse(db, serverOpt.get());
            return accepted(request, String.valueOf(resp.get("id")), resp);
        } catch (Exception e) {
            LOG.errorf(e, "Error creating database %s on server %s", dbName, serverName);
            return Response.status(500).entity(Map.of("error", String.valueOf(e.getMessage()))).build();
        }
    }

    private Response getDatabase(AzureRequest request, String serverName, String dbName) {
        Optional<PostgresState.ServerEntry> serverOpt = server(request, serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");
        return state.getDatabase(serverName, dbName)
            .map(db -> Response.ok(databaseResponse(db, serverOpt.get())).build())
            .orElse(notFound("Database '" + dbName + "' not found on server '" + serverName + "'"));
    }

    private Response deleteDatabase(AzureRequest request, String serverName, String dbName) {
        Optional<PostgresState.ServerEntry> serverOpt = server(request, serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");
        if (!state.databaseExists(serverName, dbName))
            return notFound("Database '" + dbName + "' not found");
        state.removeDatabase(serverName, dbName);
        return Response.status(204).build();
    }

    private Response handleDatabaseList(AzureRequest request, String serverName) {
        Optional<PostgresState.ServerEntry> serverOpt = server(request, serverName);
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
            PostgresState.FirewallRule rule = new PostgresState.FirewallRule(ruleName, start, end);
            state.putFirewallRule(serverName, rule);
            Map<String, Object> resp = firewallRuleResponse(rule, serverName);
            return accepted(request, String.valueOf(resp.get("id")), resp);
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
                    Map<String, Object> resp = configurationResponse(serverName, cfgName, value);
                    yield accepted(request, String.valueOf(resp.get("id")), resp);
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

    private Response handleServerConnect(AzureRequest request, String serverName) {
        return server(request, serverName)
            .map(s -> {
                PostgresConnectionInfo info = PostgresConnectionInfo.of(
                    s.fullyQualifiedDomainName(), s.hostPort(),
                    s.administratorLogin(), s.administratorLoginPassword(), null);
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("server", s.serverName());
                resp.put("host", info.host());
                resp.put("port", info.port());
                resp.put("jdbcUrl", info.jdbcUrl());
                resp.put("uri", info.uri());
                resp.put("psql", info.psql());
                resp.put("dotNet", info.dotNet());
                return Response.ok(resp).build();
            })
            .orElse(notFound("Server '" + serverName + "' not found"));
    }

    // ── Response builders ─────────────────────────────────────────────────────

    private Map<String, Object> serverResponse(PostgresState.ServerEntry s) {
        boolean ready = config.services().postgres().mocked() || s.containerId() != null;

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("administratorLogin", s.administratorLogin());
        props.put("version", s.version());
        props.put("fullyQualifiedDomainName", s.fullyQualifiedDomainName());
        props.put("state", ready ? "Ready" : "Creating");
        props.put("provisioningState", ready ? "Succeeded" : "Creating");
        props.put("storage", Map.of("storageSizeGB", s.storageSizeGB()));
        props.put("network", Map.of("publicNetworkAccess", "Enabled"));
        // floci-az convenience — not in the real spec. This is the reachable port (the published
        // host port for host networking, or the in-network container port when floci-az runs in a
        // container). Prefer the /connect endpoint, which returns the matching reachable host.
        if (s.hostPort() > 0) props.put("localPort", s.hostPort());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", s.armId());
        resp.put("name", s.serverName());
        resp.put("type", TYPE);
        resp.put("location", s.location());
        resp.put("sku", Map.of("name", s.skuName(), "tier", s.skuTier()));
        if (!s.tags().isEmpty()) resp.put("tags", s.tags());
        resp.put("properties", props);
        return resp;
    }

    private Map<String, Object> databaseResponse(PostgresState.DatabaseEntry db,
                                                 PostgresState.ServerEntry server) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", server.armId() + "/databases/" + db.databaseName());
        resp.put("name", db.databaseName());
        resp.put("type", "Microsoft.DBforPostgreSQL/flexibleServers/databases");
        resp.put("properties", Map.of(
            "charset", db.charset(),
            "collation", db.collation()));
        return resp;
    }

    private Map<String, Object> firewallRuleResponse(PostgresState.FirewallRule rule, String serverName) {
        Optional<PostgresState.ServerEntry> s = state.getServer(serverName);
        String armId = s.map(e -> e.armId() + "/firewallRules/" + rule.name())
            .orElse("/providers/Microsoft.DBforPostgreSQL/flexibleServers/" + serverName
                + "/firewallRules/" + rule.name());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", armId);
        resp.put("name", rule.name());
        resp.put("type", "Microsoft.DBforPostgreSQL/flexibleServers/firewallRules");
        resp.put("properties", Map.of(
            "startIpAddress", rule.startIpAddress(),
            "endIpAddress", rule.endIpAddress()));
        return resp;
    }

    private Map<String, Object> configurationResponse(String serverName, String cfgName, String value) {
        Optional<PostgresState.ServerEntry> s = state.getServer(serverName);
        String armId = s.map(e -> e.armId() + "/configurations/" + cfgName)
            .orElse("/providers/Microsoft.DBforPostgreSQL/flexibleServers/" + serverName
                + "/configurations/" + cfgName);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", armId);
        resp.put("name", cfgName);
        resp.put("type", "Microsoft.DBforPostgreSQL/flexibleServers/configurations");
        resp.put("properties", Map.of(
            "value", value,
            "source", "user-override"));
        return resp;
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private static String extractPgPath(String fullPath) {
        if (fullPath == null) return "";
        return ArmPaths.afterProvider(fullPath, NAMESPACE, fullPath);
    }

    private static String extractSubscriptionId(String fullPath) {
        return ArmPaths.segmentAfter(fullPath, "subscriptions", "default");
    }

    private static String extractResourceGroup(String fullPath) {
        return ArmPaths.resourceGroup(fullPath, "default");
    }

    /** Returns the n-th slash-separated segment of a path (0-based). */
    private static String segment(String path, int index) {
        String[] parts = path.split("/");
        return index < parts.length ? parts[index] : "";
    }

    @SuppressWarnings("unchecked")
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


    private void stopQuietly(PostgresState.ServerEntry entry) {
        try {
            serverManager.stopServer(entry);
        } catch (Exception e) {
            LOG.warnf(e, "Error stopping PostgreSQL container for server %s", entry.serverName());
        }
    }

    /**
     * The server a path addresses. An ARM path reaches the server stored under its own id, so a server of that name
     * in another subscription or resource group is not found; a subscription-level path names no resource group and
     * owns nothing ({@link ArmScope}). The account path ({@code /{account}-postgres/...}) has no scope and finds the
     * server by its name alone, which is unique.
     */
    private Optional<PostgresState.ServerEntry> server(AzureRequest request, String serverName) {
        Optional<ArmScope> scope = scope(request);
        if (scope.isEmpty()) {
            return state.getServer(serverName);
        }
        if (scope.get().resourceGroup() == null) {
            return Optional.empty();
        }
        return state.getServer(PostgresState.serverId(scope.get().subscription(), scope.get().resourceGroup(),
            serverName));
    }

    /**
     * A create that found the name held by a server this path cannot reach: the type's own conflict for a create in
     * a resource group, and a 404 for a path that names none, which creates nothing of its own.
     */
    private static Response nameTaken(AzureRequest request, String serverName) {
        if (scope(request).filter(armScope -> armScope.resourceGroup() == null).isPresent()) {
            return notFound("Server '" + serverName + "' not found");
        }
        GlobalName global = PostgresArmSurface.SERVER_DNS_NAME;
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
        return ArmErrors.notFound(message);
    }

    private static Response badRequest(String message) {
        return ArmErrors.error(400, "InvalidRequest", message);
    }

    private static Response methodNotAllowed() {
        return Response.status(405).entity(Map.of("error", "Method not allowed")).build();
    }

    private static Response serviceDisabled() {
        return Response.status(503).entity(Map.of(
            "error", Map.of("code", "ServiceDisabled",
                "message", "Azure Database for PostgreSQL service is disabled on this emulator."))).build();
    }

    /**
     * Stops all running PostgreSQL containers and wipes state.
     * Used by {@code POST /_admin/reset} for test isolation.
     */
    public void clear() {
        state.listServers().forEach(entry -> {
            try { serverManager.stopServer(entry); } catch (Exception e) {
                LOG.warnf(e, "Error stopping PostgreSQL container during reset: server=%s", entry.serverName());
            }
        });
        state.clear();
        startLocks.clear();
    }

    // ── ResourceIndexContributor ────────────────────────────────────────────────

    @Override
    public boolean indexEnabled() {
        return config.services().postgres().enabled();
    }

    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return indexEntries(state.listServersByResourceGroup(sub, rg));
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return indexEntries(state.listServersBySubscription(sub));
    }

    private static List<Map<String, Object>> indexEntries(List<PostgresState.ServerEntry> servers) {
        return servers.stream()
                .map(server -> ArmResources.indexEntry(server.armId(), server.serverName(), TYPE,
                        server.location(), server.tags()))
                .toList();
    }

    // ── GlobalNameSource ────────────────────────────────────────────────────────

    @Override
    public List<Claim> globalNames() {
        return state.listServers().stream()
                .map(server -> new Claim(CloudSuffix.POSTGRES, server.serverName(), PostgresState.serverId(server)))
                .toList();
    }

    @Override
    public boolean holds(CloudSuffix suffix, String name, ArmResourceId owner) {
        return suffix == CloudSuffix.POSTGRES
            && state.getServer(owner).filter(server -> name.equalsIgnoreCase(server.serverName())).isPresent();
    }
}
