package io.floci.az.services.sql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.RequestUrls;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.floci.az.config.EmulatorConfig.SqlDataPlaneProvider.EXTERNAL;
import static io.floci.az.config.EmulatorConfig.SqlDataPlaneProvider.NONE;

/**
 * HTTP handler for Azure SQL Database management-plane requests.
 *
 * <h2>Routing</h2>
 * <p>Two path styles are accepted:</p>
 * <ol>
 *   <li><b>ARM paths</b> (real Azure SDK / CLI / Terraform):
 *       {@code subscriptions/{sub}/[resourceGroups/{rg}/]providers/Microsoft.Sql/…}</li>
 *   <li><b>Convenience paths</b> (floci-az style):
 *       {@code /{account}-sql/…}</li>
 * </ol>
 *
 * <h2>Implemented operations</h2>
 * <ul>
 *   <li>Servers: create (PUT), get, list, delete, checkNameAvailability</li>
 *   <li>Databases: create (PUT), update (PATCH), get, list, delete</li>
 *   <li>Firewall rules: create, get, list, delete</li>
 *   <li>Settings ({@link SqlSettingType}): the connection policy and Express vulnerability assessment of a server;
 *       the transparent data encryption, security alert policy, short and long term retention policies and geo
 *       backup policy of a database. Each is stored on its parent's record and read with its defaults until
 *       written</li>
 *   <li>Restorable dropped databases: always an empty list</li>
 *   <li>Convenience: {@code /connect}, which returns all connection string formats</li>
 * </ul>
 */
@ApplicationScoped
public class SqlHandler implements AzureServiceHandler, Resettable, ResourceIndexContributor, GlobalNameSource {

    private static final Logger LOG = Logger.getLogger(SqlHandler.class);
    private static final String TYPE = "Microsoft.Sql/servers";
    private static final TypeReference<Map<String, Object>> PROPERTIES = new TypeReference<>() { };

    private final EmulatorConfig config;
    private final SqlState state;
    private final SqlServerManager serverManager;
    private final SqlProvisioningService provisioningService;
    private final GlobalNameRegistry names;
    private final ObjectMapper mapper;

    @Inject
    public SqlHandler(EmulatorConfig config, SqlState state, SqlServerManager serverManager,
                      SqlProvisioningService provisioningService, GlobalNameRegistry names) {
        this.config = config;
        this.state = state;
        this.serverManager = serverManager;
        this.provisioningService = provisioningService;
        this.names = names;
        this.mapper = new ObjectMapper();
    }

    @Override public String getServiceType()           { return "sql"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().sql().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .account("-sql", "sql")
                .provider("Microsoft.Sql")
                .build();
    }

    @Override
    public Response handle(AzureRequest request) {
        // Extract the "tail" after Microsoft.Sql/ for ARM paths,
        // or use resourcePath directly for /{account}-sql/ paths.
        String tail = extractSqlPath(request.resourcePath());
        String method = request.method();

        LOG.debugf("SqlHandler: %s %s → tail=%s", method, request.resourcePath(), tail);

        // ── checkNameAvailability ──────────────────────────────────────────
        if ("checkNameAvailability".equalsIgnoreCase(tail) && "POST".equals(method)) {
            return handleCheckNameAvailability(request);
        }

        if (outOfScope(request, tail)) {
            return notFound("Server '" + segment(tail, 1) + "' not found");
        }

        // Azure SQL server create long-running operation polling.
        if (tail.matches("locations/[^/]+/serverOperationResults/[^/]+")
            && "GET".equals(method)) {
            return getProvisioningOperation(request, segment(tail, 3));
        }

        // ── Convenience /connect (server level) ───────────────────────────
        // /{account}-sql/servers/{server}/connect
        if (tail.matches("servers/[^/]+/connect")) {
            String serverName = segment(tail, 1);
            return handleServerConnect(serverName);
        }

        // ── Convenience /connect (database level) ─────────────────────────
        // /{account}-sql/servers/{server}/databases/{db}/connect
        if (tail.matches("servers/[^/]+/databases/[^/]+/connect")) {
            String serverName = segment(tail, 1);
            String dbName     = segment(tail, 3);
            return handleDatabaseConnect(serverName, dbName);
        }

        // ── Database settings: transparentDataEncryption/current, securityAlertPolicies/default, ... ──
        if (tail.matches("servers/[^/]+/databases/[^/]+/[^/]+(/[^/]+)?")) {
            Optional<SqlSettingType> type = SqlSettingType.of(SqlSettingType.Parent.DATABASE, segment(tail, 4));
            if (type.isPresent()) {
                return handleDatabaseSetting(method, request, segment(tail, 1), segment(tail, 3), type.get(),
                    optionalSegment(tail, 5));
            }
        }

        // ── Server settings: connectionPolicies/default, sqlVulnerabilityAssessments/default ──
        if (tail.matches("servers/[^/]+/[^/]+(/[^/]+)?")) {
            Optional<SqlSettingType> type = SqlSettingType.of(SqlSettingType.Parent.SERVER, segment(tail, 2));
            if (type.isPresent()) {
                return handleServerSetting(method, request, segment(tail, 1), type.get(), optionalSegment(tail, 3));
            }
        }

        // ── Restorable dropped databases: dropped databases are not kept, so there are none ──
        if (tail.matches("servers/[^/]+/restorableDroppedDatabases(/[^/]+)?")) {
            return handleRestorableDroppedDatabases(method, segment(tail, 1), optionalSegment(tail, 3));
        }

        // ── Firewall rules ────────────────────────────────────────────────
        if (tail.matches("servers/[^/]+/firewallRules/[^/]+")) {
            String serverName = segment(tail, 1);
            String ruleName   = segment(tail, 3);
            return handleFirewallRule(method, request, serverName, ruleName);
        }
        if (tail.matches("servers/[^/]+/firewallRules")) {
            String serverName = segment(tail, 1);
            return handleFirewallRuleList(method, request, serverName);
        }

        // ── Databases ─────────────────────────────────────────────────────
        if (tail.matches("servers/[^/]+/databases/[^/]+")) {
            String serverName = segment(tail, 1);
            String dbName     = segment(tail, 3);
            return handleDatabase(method, request, serverName, dbName);
        }
        if (tail.matches("servers/[^/]+/databases")) {
            String serverName = segment(tail, 1);
            return handleDatabaseList(serverName);
        }

        // ── Servers ───────────────────────────────────────────────────────
        if (tail.matches("servers/[^/]+")) {
            String serverName = segment(tail, 1);
            return handleServer(method, request, serverName);
        }
        if ("servers".equalsIgnoreCase(tail) || tail.isEmpty()) {
            return handleServerList(request);
        }

        return notFound("The resource 'Microsoft.Sql/" + tail + "' was not found.");
    }

    // ── checkNameAvailability ─────────────────────────────────────────────────

    private Response handleCheckNameAvailability(AzureRequest request) {
        try {
            JsonNode body = readBody(request.bodyStream());
            String name = body.path("name").asText();
            boolean available = names.owner(cloud(request), CloudSuffix.SQL, name).isEmpty();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("available", available);
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
            case "PUT"    -> createOrUpdateServer(request, serverName);
            case "GET"    -> getServer(serverName);
            case "DELETE" -> deleteServer(request, serverName);
            default       -> methodNotAllowed(method);
        };
    }

    private synchronized Response createOrUpdateServer(AzureRequest request, String serverName) {
        if (!config.services().sql().enabled()) {
            return serviceDisabled();
        }
        if (config.services().sql().dataPlaneProvider() == EXTERNAL) {
            return dataPlaneProviderUnavailable();
        }

        try {
            JsonNode body = readBody(request.bodyStream());
            String location  = body.path("location").asText("eastus");
            JsonNode props   = body.path("properties");
            Optional<SqlState.SqlServerEntry> current = state.getServer(serverName);
            // The login and password are required to create a server only ("required for server creation"): an
            // update, such as azurerm's PUT of the server it read back, which carries no password, keeps them.
            String login     = props.path("administratorLogin").asText(
                current.map(SqlState.SqlServerEntry::administratorLogin).orElse(""));
            String password  = props.path("administratorLoginPassword").asText(
                current.map(SqlState.SqlServerEntry::administratorLoginPassword).orElse(""));

            if (login.isBlank()) {
                return badRequest("administratorLogin is required");
            }
            if (password.isBlank()) {
                return badRequest("administratorLoginPassword is required");
            }

            Map<String, String> tags = parseTags(body.path("tags"));
            // A field the PUT leaves out keeps its stored value; azurerm reads minimalTlsVersion as "cannot be
            // removed once set".
            SqlState.SqlServerAccess access = new SqlState.SqlServerAccess(
                    textOrNull(props.path("minimalTlsVersion")),
                    textOrNull(props.path("publicNetworkAccess")),
                    textOrNull(props.path("restrictOutboundNetworkAccess")))
                .orElse(current.map(SqlState.SqlServerEntry::access).orElse(SqlState.SqlServerAccess.DEFAULT));
            // The account path names no scope: it updates the server of this name where it is stored, so the name
            // keeps its owner. A create there goes to the default scope.
            boolean accountPath = scope(request).isEmpty();
            String sub = accountPath && current.isPresent() ? current.get().subscriptionId()
                : extractSubscriptionId(request.resourcePath());
            String rg  = accountPath && current.isPresent() ? current.get().resourceGroupName()
                : extractResourceGroup(request.resourcePath());

            // The name is reserved in the GlobalNameRegistry by the gate's GlobalNameRule before an ARM create
            // runs, so a name another scope owns never reaches here. A create outside the gate reserves it here
            // (an ARM create finds its own reservation OWNED), so it cannot overtake an ARM create in flight.
            ArmResourceId id = serverId(sub, rg, serverName);
            long reservation = 0;
            if (current.isEmpty()) {
                GlobalNameRegistry.Reservation reserved = names.reserve(cloud(request), CloudSuffix.SQL, serverName,
                    id);
                if (reserved.outcome() == GlobalNameRegistry.Outcome.TAKEN) {
                    return nameTaken(serverName);
                }
                reservation = reserved.token();
            }
            try {
                if (config.services().sql().dataPlaneProvider() == NONE) {
                    // A server of this name under another id (a create through the convenience paths) moves here,
                    // keeping its databases and firewall rules: one server per name.
                    Optional<SqlState.SqlServerEntry> named = current;
                    boolean created = state.createOrUpdateServer(id, existing -> {
                        SqlState.SqlServerEntry ready = desiredEntry(existing.or(() -> named), serverName, sub, rg,
                            location, login, password, tags, access, "Ready");
                        ready.databases().putIfAbsent("master", SqlState.SqlDatabaseEntry.master(serverName));
                        return ready;
                    });
                    boolean isNew = created && current.isEmpty();
                    holdName(request, id);
                    SqlState.SqlServerEntry ready = state.getServer(id).orElseThrow();
                    return Response.status(isNew ? 201 : 200).entity(serverResponse(ready)).build();
                }

                serverManager.requireEulaAccepted();
                if (current.isPresent() && "Creating".equals(current.get().provisioningState())) {
                    Optional<SqlProvisioningService.SqlProvisioningOperation> active =
                        provisioningService.activeOperation(serverName);
                    if (active.isPresent()) {
                        if (!equivalent(current.get(), sub, rg, location, login, password, tags, access)) {
                            return ArmErrors.error(409, "ConflictingServerOperation",
                                "A conflicting create or update operation is in progress for SQL server '"
                                    + serverName + "'.");
                        }
                        return accepted(request, active.get(), current.get());
                    }
                    current = state.getServer(serverName);
                }

                if (current.isPresent()
                        && "Ready".equals(current.get().provisioningState())
                        && hasDataPlaneRuntime(current.get())) {
                    SqlState.SqlServerEntry updated = desiredEntry(current, serverName, sub, rg,
                        location, login, password, tags, access, "Ready");
                    state.updateServer(updated);
                    holdName(request, id);
                    return Response.ok(serverResponse(updated)).build();
                }

                SqlState.SqlServerEntry desired = desiredEntry(current, serverName, sub, rg,
                    location, login, password, tags, access, "Creating");
                state.updateServer(desired);
                holdName(request, id);
                SqlProvisioningService.SqlProvisioningOperation operation =
                    provisioningService.begin(desired);
                return accepted(request, operation, desired);
            } finally {
                // A no-op once the create committed the name, and for a reservation this request did not make.
                names.releasePending(cloud(request), CloudSuffix.SQL, serverName, reservation);
            }
        } catch (SqlServerManager.EulaNotAcceptedException e) {
            return ArmErrors.error(503, "EulaNotAccepted", e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "Error creating SQL server %s", serverName);
            return ArmErrors.error(500, "InternalServerError", e.getMessage());
        }
    }

    private Response getProvisioningOperation(AzureRequest request, String operationId) {
        return provisioningService.get(operationId)
            .map(operation -> operationResponse(request, operation))
            .orElse(ArmErrors.error(404, "OperationIdNotFound",
                "SQL provisioning operation '" + operationId + "' was not found."));
    }

    private Response operationResponse(AzureRequest request,
                                       SqlProvisioningService.SqlProvisioningOperation operation) {
        if (operation.errorCode() != null) {
            return ArmErrors.error(500, operation.errorCode(), operation.errorMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", operation.id());
        body.put("status", operation.status());
        body.put("startTime", operation.startTime());
        if (operation.endTime() != null) {
            body.put("endTime", operation.endTime());
        }
        Response.ResponseBuilder response = Response.status(operation.inProgress() ? 202 : 200)
            .entity(body);
        if (operation.inProgress()) {
            response.header("Location", operationLocation(request, operation));
            response.header("Retry-After", "1");
        }
        return response.build();
    }

    private Response accepted(AzureRequest request,
                              SqlProvisioningService.SqlProvisioningOperation operation,
                              SqlState.SqlServerEntry server) {
        return Response.status(202)
            .header("Location", operationLocation(request, operation))
            .header("Retry-After", "1")
            .entity(serverResponse(server))
            .build();
    }

    private String operationLocation(AzureRequest request,
                                     SqlProvisioningService.SqlProvisioningOperation operation) {
        String host = RequestUrls.resolveAuthority(request);
        if (host == null || host.isBlank()) {
            host = "localhost:" + config.port();
        }
        String scheme = request.headers().getHeaderString("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = request.secure() ? "https" : "http";
        }
        String apiVersion = request.queryParams().getOrDefault("api-version", "2021-11-01");
        String location = operation.location().toLowerCase(Locale.ROOT).replace(" ", "");
        return scheme + "://" + host + "/subscriptions/"
            + extractSubscriptionId(operation.resourceId())
            + "/resourceGroups/" + extractResourceGroup(operation.resourceId())
            + "/providers/Microsoft.Sql/locations/" + location
            + "/serverOperationResults/" + operation.id() + "?api-version=" + apiVersion;
    }

    private static SqlState.SqlServerEntry desiredEntry(
            Optional<SqlState.SqlServerEntry> current, String serverName, String subscriptionId,
            String resourceGroup, String location, String login, String password,
            Map<String, String> tags, SqlState.SqlServerAccess access, String provisioningState) {
        Map<String, SqlState.SqlDatabaseEntry> databases = current
            .map(SqlState.SqlServerEntry::databases)
            .orElseGet(ConcurrentHashMap::new);
        Map<String, SqlState.SqlFirewallRule> firewallRules = current
            .map(SqlState.SqlServerEntry::firewallRules)
            .orElseGet(ConcurrentHashMap::new);
        Map<String, SqlState.SqlSetting> settings = current
            .map(SqlState.SqlServerEntry::settings)
            .orElseGet(ConcurrentHashMap::new);
        Instant createdAt = current.map(SqlState.SqlServerEntry::createdAt).orElseGet(Instant::now);
        String containerId = current.map(SqlState.SqlServerEntry::containerId).orElse(null);
        int hostPort = current.map(SqlState.SqlServerEntry::hostPort).orElse(0);
        String host = current.map(SqlState.SqlServerEntry::host).orElse("localhost");
        return new SqlState.SqlServerEntry(serverName, subscriptionId, resourceGroup, location,
            login, password, containerId, hostPort, host, provisioningState, null, null,
            tags, databases, firewallRules, createdAt, access, settings);
    }

    private static boolean equivalent(SqlState.SqlServerEntry current, String subscriptionId,
                                      String resourceGroup, String location, String login,
                                      String password, Map<String, String> tags,
                                      SqlState.SqlServerAccess access) {
        return current.subscriptionId().equalsIgnoreCase(subscriptionId)
            && current.resourceGroupName().equalsIgnoreCase(resourceGroup)
            && current.location().equalsIgnoreCase(location)
            && current.administratorLogin().equals(login)
            && current.administratorLoginPassword().equals(password)
            && current.tags().equals(tags)
            && sameText(current.access().minimalTlsVersion(), access.minimalTlsVersion())
            && sameText(current.access().publicNetworkAccess(), access.publicNetworkAccess())
            && sameText(current.access().restrictOutboundNetworkAccess(), access.restrictOutboundNetworkAccess());
    }

    private static boolean sameText(String stored, String requested) {
        return stored == null ? requested == null : stored.equalsIgnoreCase(requested);
    }

    private static boolean hasDataPlaneRuntime(SqlState.SqlServerEntry server) {
        return server.containerId() != null && server.hostPort() > 0;
    }

    private Response getServer(String serverName) {
        return state.getServer(serverName)
            .map(s -> Response.ok(serverResponse(s)).build())
            .orElse(notFound("Server '" + serverName + "' not found"));
    }

    private synchronized Response deleteServer(AzureRequest request, String serverName) {
        if (!state.serverExists(serverName)) {
            return notFound("Server '" + serverName + "' not found");
        }
        provisioningService.cancel(serverName);
        Optional<SqlState.SqlServerEntry> entry = state.getServer(serverName);
        state.removeServer(serverName);
        entry.ifPresent(server -> names.release(cloud(request), CloudSuffix.SQL, serverName, serverId(server)));
        entry.ifPresent(server -> {
            try {
                serverManager.stopServer(server);
            } catch (Exception e) {
                LOG.warnf(e, "Error stopping SQL container for server %s", serverName);
            }
        });
        return Response.status(204).build();
    }

    private Response handleServerList(AzureRequest request) {
        String sub = extractSubscriptionId(request.resourcePath());
        // A path with no resourceGroups segment lists the subscription; a group named "default" is a group.
        Optional<String> rg = request.resourceGroup();
        List<SqlState.SqlServerEntry> servers = rg.isEmpty()
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
            case "PATCH"  -> updateDatabase(request, serverName, dbName);
            case "GET"    -> getDatabase(serverName, dbName);
            case "DELETE" -> deleteDatabase(serverName, dbName);
            default       -> methodNotAllowed(method);
        };
    }

    private Response createOrUpdateDatabase(AzureRequest request, String serverName, String dbName) {
        Optional<SqlState.SqlServerEntry> serverOpt = state.getServer(serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");

        try {
            SqlState.SqlDatabaseRequest requested = databaseRequest(readBody(request.bodyStream()));
            // The emulator tracks the database in state only.
            // Actual CREATE DATABASE is the responsibility of the application
            // (Flyway, Liquibase, EF Core, etc.) using the JDBC URL from /connect.
            // An update keeps the database's id, creation date and settings, read in the same step as the write.
            Optional<SqlState.DatabaseWrite> written = state.createOrUpdateDatabase(serverName, dbName, existing ->
                existing.map(current -> current.updatedWith(requested, true))
                    .orElseGet(() -> SqlState.SqlDatabaseEntry.create(dbName, serverName, requested)));
            if (written.isEmpty()) {
                return notFound("Server '" + serverName + "' not found");
            }

            return Response.status(written.get().created() ? 201 : 200)
                .entity(databaseResponse(written.get().database(), serverOpt.get()))
                .build();

        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            LOG.errorv(e, "Error creating database {0} on server {1}", dbName, serverName);
            return ArmErrors.error(500, "InternalServerError", e.getMessage());
        }
    }

    /** {@code Databases_Update}: the fields the body names over the stored database; 200 with the result. */
    private Response updateDatabase(AzureRequest request, String serverName, String dbName) {
        Optional<SqlState.SqlServerEntry> serverOpt = state.getServer(serverName);
        if (serverOpt.isEmpty()) {
            return notFound("Server '" + serverName + "' not found");
        }
        SqlState.SqlDatabaseRequest requested = databaseRequest(readBody(request.bodyStream()));
        return state.updateDatabase(serverName, dbName, current -> current.updatedWith(requested, false))
            .map(db -> Response.ok(databaseResponse(db, serverOpt.get())).build())
            .orElseGet(() -> notFound("Database '" + dbName + "' not found on server '" + serverName + "'"));
    }

    /** The database fields of a PUT or PATCH body; one the body leaves out is null. */
    private static SqlState.SqlDatabaseRequest databaseRequest(JsonNode body) {
        JsonNode props = body.path("properties");
        return new SqlState.SqlDatabaseRequest(
            textOrNull(props.path("collation")),
            textOrNull(props.path("edition")),
            textOrNull(body.path("sku").path("name")),
            longOrNull(props.path("maxSizeBytes")),
            textOrNull(props.path("requestedBackupStorageRedundancy")),
            props.path("zoneRedundant").isBoolean() ? props.path("zoneRedundant").asBoolean() : null,
            body.path("tags").isObject() ? parseTags(body.path("tags")) : null);
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    /** An integer, or its decimal string (the shape this emulator once answered). */
    private static Long longOrNull(JsonNode node) {
        if (node.canConvertToLong() && node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual() && node.asText().matches("\\d{1,18}")) {
            return Long.parseLong(node.asText());
        }
        return null;
    }

    private Response getDatabase(String serverName, String dbName) {
        Optional<SqlState.SqlServerEntry> serverOpt = state.getServer(serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");
        return state.getDatabase(serverName, dbName)
            .map(db -> Response.ok(databaseResponse(db, serverOpt.get())).build())
            .orElse(notFound("Database '" + dbName + "' not found on server '" + serverName + "'"));
    }

    private Response deleteDatabase(String serverName, String dbName) {
        if ("master".equalsIgnoreCase(dbName)) return badRequest("Cannot drop the master database");
        Optional<SqlState.SqlServerEntry> serverOpt = state.getServer(serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");
        if (!state.databaseExists(serverName, dbName))
            return notFound("Database '" + dbName + "' not found");
        // Remove from state only: actual DROP DATABASE is the application's responsibility.
        state.removeDatabase(serverName, dbName);
        return Response.status(204).build();
    }

    private Response handleDatabaseList(String serverName) {
        if (!state.serverExists(serverName))
            return notFound("Server '" + serverName + "' not found");
        Optional<SqlState.SqlServerEntry> serverOpt = state.getServer(serverName);
        List<Map<String, Object>> value = state.listDatabases(serverName).stream()
            .map(db -> databaseResponse(db, serverOpt.get()))
            .toList();
        return Response.ok(Map.of("value", value)).build();
    }

    // ── Firewall rules ────────────────────────────────────────────────────────

    private Response handleFirewallRule(String method, AzureRequest request,
                                         String serverName, String ruleName) {
        if (!state.serverExists(serverName))
            return notFound("Server '" + serverName + "' not found");
        return switch (method) {
            case "PUT"    -> createFirewallRule(request, serverName, ruleName);
            case "GET"    -> state.getFirewallRule(serverName, ruleName)
                                  .map(r -> Response.ok(firewallRuleResponse(r, serverName)).build())
                                  .orElse(notFound("Firewall rule '" + ruleName + "' not found"));
            case "DELETE" -> {
                state.removeFirewallRule(serverName, ruleName);
                yield Response.status(204).build();
            }
            default -> methodNotAllowed(method);
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
            SqlState.SqlFirewallRule rule = new SqlState.SqlFirewallRule(ruleName, start, end);
            state.putFirewallRule(serverName, rule);
            return Response.status(201).entity(firewallRuleResponse(rule, serverName)).build();
        } catch (Exception e) {
            return badRequest("Invalid firewall rule body: " + e.getMessage());
        }
    }

    private Response handleFirewallRuleList(String method, AzureRequest request, String serverName) {
        if (!state.serverExists(serverName))
            return notFound("Server '" + serverName + "' not found");
        if ("GET".equals(method)) {
            List<Map<String, Object>> value = state.listFirewallRules(serverName).stream()
                .map(r -> firewallRuleResponse(r, serverName))
                .toList();
            return Response.ok(Map.of("value", value)).build();
        }
        return methodNotAllowed(method);
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private Response handleServerSetting(String method, AzureRequest request, String serverName,
                                         SqlSettingType type, String name) {
        Optional<SqlState.SqlServerEntry> server = state.getServer(serverName);
        if (server.isEmpty()) {
            return notFound("Server '" + serverName + "' not found");
        }
        SqlState.SqlServerEntry entry = server.get();
        return handleSetting(method, request, type, name, entry.armId(), entry.location(),
            entry.settings().get(type.key()),
            change -> state.updateServerSetting(serverName, type, change));
    }

    private Response handleDatabaseSetting(String method, AzureRequest request, String serverName, String dbName,
                                           SqlSettingType type, String name) {
        Optional<SqlState.SqlServerEntry> server = state.getServer(serverName);
        if (server.isEmpty()) {
            return notFound("Server '" + serverName + "' not found");
        }
        Optional<SqlState.SqlDatabaseEntry> database = state.getDatabase(serverName, dbName);
        if (database.isEmpty()) {
            return notFound("Database '" + dbName + "' not found on server '" + serverName + "'");
        }
        Map<String, SqlState.SqlSetting> stored = database.get().settings();
        return handleSetting(method, request, type, name,
            server.get().armId() + "/databases/" + database.get().databaseName(), server.get().location(),
            stored == null ? null : stored.get(type.key()),
            change -> state.updateDatabase(serverName, dbName, db -> db.withSetting(type,
                    change.apply(db.settings() == null ? null : db.settings().get(type.key()))))
                .map(db -> db.settings().get(type.key())));
    }

    /**
     * One setting of a server or a database: GET answers it (its defaults when never written), PUT replaces it,
     * PATCH merges into it where the spec declares one. {@code name} is null for the list of the parent's settings
     * of this type, which holds the one. {@code update} applies a change to the setting as stored at that moment and
     * writes it in one step, so concurrent writes of different fields both land; empty when the parent is gone.
     */
    private Response handleSetting(String method, AzureRequest request, SqlSettingType type, String name,
                                   String parentId, String location, SqlState.SqlSetting stored,
                                   Function<UnaryOperator<SqlState.SqlSetting>, Optional<SqlState.SqlSetting>> update) {
        Map<String, Object> current = type.properties(stored);
        if (name == null) {
            if (!"GET".equals(method)) {
                return methodNotAllowed(method);
            }
            return Response.ok(Map.of("value", List.of(settingResponse(type, parentId, location, current)))).build();
        }
        if (!type.isNamed(name)) {
            return notFound("The resource '" + type.armType() + "/" + name + "' under '" + parentId
                + "' was not found; its only name is '" + type.fixedName() + "'.");
        }
        boolean replace = "PUT".equals(method);
        return switch (method) {
            case "GET" -> Response.ok(settingResponse(type, parentId, location, current)).build();
            case "PUT", "PATCH" -> {
                if (!replace && !type.patchable()) {
                    yield methodNotAllowed(method);
                }
                Map<String, Object> requested = settingProperties(request);
                Optional<SqlState.SqlSetting> written =
                    update.apply(latest -> type.write(type.properties(latest), requested, replace));
                if (written.isEmpty()) {
                    yield notFound("The parent of '" + type.armType() + "/" + name + "' under '" + parentId
                        + "' was not found.");
                }
                yield Response.ok(settingResponse(type, parentId, location, type.properties(written.get()))).build();
            }
            default -> methodNotAllowed(method);
        };
    }

    private Map<String, Object> settingProperties(AzureRequest request) {
        JsonNode props = readBody(request.bodyStream()).path("properties");
        if (!props.isObject()) {
            return Map.of();
        }
        return mapper.convertValue(props, PROPERTIES);
    }

    private static Map<String, Object> settingResponse(SqlSettingType type, String parentId, String location,
                                                       Map<String, Object> properties) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", parentId + "/" + type.segment() + "/" + type.fixedName());
        resp.put("name", type.fixedName());
        resp.put("type", type.armType());
        // ServerConnectionPolicy is the one setting whose spec model carries a (read-only) location.
        if (type == SqlSettingType.CONNECTION_POLICY) {
            resp.put("location", location);
        }
        resp.put("properties", properties);
        return resp;
    }

    /**
     * {@code RestorableDroppedDatabases_ListByServer} and {@code _Get}: a deleted database is not kept, so the list
     * is empty and any one is not found. azurerm reads the list on every server refresh.
     */
    private Response handleRestorableDroppedDatabases(String method, String serverName, String id) {
        if (!state.serverExists(serverName)) {
            return notFound("Server '" + serverName + "' not found");
        }
        if (!"GET".equals(method)) {
            return methodNotAllowed(method);
        }
        if (id == null) {
            return Response.ok(Map.of("value", List.of())).build();
        }
        return notFound("Restorable dropped database '" + id + "' not found on server '" + serverName + "'");
    }

    // ── Convenience /connect ──────────────────────────────────────────────────

    private Response handleServerConnect(String serverName) {
        Optional<SqlState.SqlServerEntry> server = state.getServer(serverName);
        if (server.isEmpty()) {
            return notFound("Server '" + serverName + "' not found");
        }
        return connectResponse(server.get(), null);
    }

    private Response handleDatabaseConnect(String serverName, String dbName) {
        Optional<SqlState.SqlServerEntry> serverOpt = state.getServer(serverName);
        if (serverOpt.isEmpty()) {
            return notFound("Server '" + serverName + "' not found");
        }
        Optional<SqlState.SqlDatabaseEntry> dbOpt = state.getDatabase(serverName, dbName);
        if (dbOpt.isEmpty()) {
            return notFound("Database '" + dbName + "' not found");
        }

        return connectResponse(serverOpt.get(), dbName);
    }

    // ── Response builders ─────────────────────────────────────────────────────

    private Map<String, Object> serverResponse(SqlState.SqlServerEntry s) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("administratorLogin", s.administratorLogin());
        props.put("version", "12.0");
        props.put("state", s.provisioningState());
        props.put("fullyQualifiedDomainName", s.fullyQualifiedDomainName());
        props.put("minimalTlsVersion", s.access().minimalTlsVersion());
        props.put("publicNetworkAccess", s.access().publicNetworkAccess());
        props.put("restrictOutboundNetworkAccess", s.access().restrictOutboundNetworkAccess());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", s.armId());
        resp.put("name", s.serverName());
        resp.put("type", TYPE);
        resp.put("location", s.location());
        resp.put("kind", "v12.0");
        if (!s.tags().isEmpty()) resp.put("tags", s.tags());
        resp.put("properties", props);
        return resp;
    }

    private Map<String, Object> databaseResponse(SqlState.SqlDatabaseEntry db,
                                                   SqlState.SqlServerEntry server) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("collation", db.collation());
        props.put("edition", db.edition());
        props.put("status", db.status());
        props.put("databaseId", db.databaseId());
        props.put("creationDate", db.createdAt().toString());
        props.put("serviceLevelObjective", db.sku());
        props.put("currentServiceObjectiveName", db.sku());
        props.put("requestedServiceObjectiveName", db.sku());
        props.put("maxSizeBytes", db.effectiveMaxSizeBytes());
        props.put("currentBackupStorageRedundancy", db.effectiveBackupStorageRedundancy());
        props.put("requestedBackupStorageRedundancy", db.effectiveBackupStorageRedundancy());
        props.put("zoneRedundant", db.zoneRedundant());

        String dbArmId = server.armId() + "/databases/" + db.databaseName();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", dbArmId);
        resp.put("name", db.databaseName());
        resp.put("type", "Microsoft.Sql/servers/databases");
        resp.put("location", server.location());
        resp.put("kind", "v12.0,user");
        if (db.tags() != null && !db.tags().isEmpty()) {
            resp.put("tags", db.tags());
        }
        resp.put("sku", Map.of("name", db.sku(), "tier", db.edition()));
        resp.put("properties", props);
        return resp;
    }

    private Map<String, Object> firewallRuleResponse(SqlState.SqlFirewallRule rule, String serverName) {
        Optional<SqlState.SqlServerEntry> s = state.getServer(serverName);
        String armId = s.map(e -> e.armId() + "/firewallRules/" + rule.name())
                        .orElse("/providers/Microsoft.Sql/servers/" + serverName + "/firewallRules/" + rule.name());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", armId);
        resp.put("name", rule.name());
        resp.put("type", "Microsoft.Sql/servers/firewallRules");
        resp.put("kind", "v12.0");
        resp.put("properties", Map.of(
            "startIpAddress", rule.startIpAddress(),
            "endIpAddress", rule.endIpAddress()));
        return resp;
    }

    private Response connectResponse(SqlState.SqlServerEntry server, String database) {
        if (config.services().sql().dataPlaneProvider() == NONE) {
            return dataPlaneNotEnabled();
        }
        if (config.services().sql().dataPlaneProvider() == EXTERNAL) {
            return dataPlaneProviderUnavailable();
        }
        if (server.hostPort() <= 0) {
            return ArmErrors.error(409, "DataPlaneNotReady",
                "SQL data plane for server '" + server.serverName() + "' is not ready.");
        }
        SqlConnectionInfo real = SqlConnectionInfo.of(
            server.dataPlaneHost(), server.hostPort(),
            server.administratorLogin(), server.administratorLoginPassword(),
            database);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("server", server.serverName());
        resp.put("host", real.host());
        resp.put("port", real.port());
        if (database != null) resp.put("database", database);
        resp.put("jdbcUrl",       real.jdbcUrl());
        resp.put("connectionString", real.adoNet());
        resp.put("pyodbc",        real.pyodbc());
        resp.put("entityFramework", real.efCore());
        return Response.ok(resp).build();
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    /**
     * Extracts the portion of the path after {@code /providers/Microsoft.Sql/}.
     * For convenience {@code /{account}-sql/} paths the resourcePath is already
     * relative, so we return it unchanged if no ARM prefix is found.
     */
    private static String extractSqlPath(String fullPath) {
        if (fullPath == null) return "";
        // Convenience path: already relative (e.g. "servers/myserver/databases/mydb")
        return ArmPaths.afterProvider(fullPath, "Microsoft.Sql", fullPath);
    }

    private static String extractSubscriptionId(String fullPath) {
        return ArmPaths.segmentAfter(fullPath, "subscriptions", "default");
    }

    private static String extractResourceGroup(String fullPath) {
        return ArmPaths.resourceGroup(fullPath, "default");
    }

    /** The n-th slash-separated segment of a path (0-based), or null when the path is shorter. */
    private static String optionalSegment(String path, int index) {
        String[] parts = path.split("/");
        return index < parts.length ? parts[index] : null;
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


    /** The subscription and resource group of an ARM path; empty for the account path. */
    private static Optional<ArmScope> scope(AzureRequest request) {
        return ArmScope.of(request.resourcePath());
    }

    /**
     * Whether an ARM path names a server stored under another scope. Child calls look a server up by its name,
     * which is global, so a path whose scope does not hold the server of that name must not reach it: it reads as
     * a 404, as a lookup by the path's id would. A PUT of the server itself passes: the gate's GlobalNameRule has
     * already refused a name another scope owns. A path that names no resource group holds no server.
     */
    private boolean outOfScope(AzureRequest request, String tail) {
        if (!tail.matches("servers/[^/]+(/.*)?")) {
            return false;
        }
        Optional<ArmScope> scope = scope(request);
        if (scope.isEmpty()) {
            return false;
        }
        String serverName = segment(tail, 1);
        if (scope.get().resourceGroup() == null) {
            return state.serverExists(serverName);
        }
        if ("PUT".equals(request.method()) && tail.matches("servers/[^/]+")) {
            return false;
        }
        return state.serverExists(serverName)
            && state.getServer(serverId(scope.get().subscription(), scope.get().resourceGroup(), serverName))
                .isEmpty();
    }

    /**
     * Holds the server's global name for it. The gate does this for an ARM create; a create through the
     * {@code /{account}-sql/} paths or a path without a resource group passes no gate, and its name must still be
     * taken. Nothing changes when the server already holds it.
     */
    private void holdName(AzureRequest request, ArmResourceId id) {
        names.commit(cloud(request), CloudSuffix.SQL, id.name(), id);
    }

    /** A create that found the name reserved for another server: Azure SQL's name conflict. */
    private static Response nameTaken(String serverName) {
        GlobalName global = SqlArmSurface.SERVER_DNS_NAME;
        return ArmErrors.error(global.conflictStatus(), global.conflictCode(),
            String.format(global.conflictMessage(), serverName));
    }

    private static ArmResourceId serverId(String subscriptionId, String resourceGroup, String serverName) {
        return ArmResourceId.parse(String.format(
            "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Sql/servers/%s",
            subscriptionId, resourceGroup, serverName)).orElseThrow();
    }

    private static ArmResourceId serverId(SqlState.SqlServerEntry server) {
        return ArmResourceId.parse(server.armId()).orElseThrow();
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

    private static Response methodNotAllowed(String method) {
        return ArmErrors.error(405, "MethodNotAllowed",
            "The requested resource does not support http method '" + method + "'.");
    }

    private static Response serviceDisabled() {
        return ArmErrors.error(503, "ServiceDisabled", "Azure SQL Database service is disabled on this emulator.");
    }

    private static Response dataPlaneNotEnabled() {
        return ArmErrors.error(409, "DataPlaneNotEnabled",
            "Azure SQL data plane is disabled. Set floci-az.services.sql.data-plane.provider "
                + "to managed to enable connection discovery.");
    }

    private static Response dataPlaneProviderUnavailable() {
        return ArmErrors.error(503, "DataPlaneProviderUnavailable",
            "Azure SQL external data-plane provider is not configured in this version.");
    }

    /**
     * Stops all running SQL Server containers and wipes state.
     * Used by {@code POST /_admin/reset} for test isolation.
     */
    public synchronized void clear() {
        provisioningService.clear();
        state.listServers().forEach(entry -> {
            try { serverManager.stopServer(entry); } catch (Exception e) {
                LOG.warnf(e, "Error stopping SQL container during reset: server=%s", entry.serverName());
            }
        });
        state.clear();
    }

    // ── ResourceIndexContributor ────────────────────────────────────────────────

    @Override
    public boolean indexEnabled() {
        return config.services().sql().enabled();
    }

    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return indexEntries(state.listServersByResourceGroup(sub, rg));
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return indexEntries(state.listServersBySubscription(sub));
    }

    private static List<Map<String, Object>> indexEntries(List<SqlState.SqlServerEntry> servers) {
        return servers.stream()
                .map(server -> ArmResources.indexEntry(server.armId(), server.serverName(), TYPE,
                        server.location(), server.tags()))
                .toList();
    }

    // ── GlobalNameSource ────────────────────────────────────────────────────────

    @Override
    public List<Claim> globalNames() {
        return state.listServers().stream()
                .map(server -> new Claim(CloudSuffix.SQL, server.serverName(), serverId(server)))
                .toList();
    }
}
