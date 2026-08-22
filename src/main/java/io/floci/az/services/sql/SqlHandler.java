package io.floci.az.services.sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.Resettable;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmPaths;
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
 *   <li>Databases: create (PUT), get, list, delete</li>
 *   <li>Firewall rules: create, get, list, delete</li>
 *   <li>Connection policy: get/put (always returns "Default")</li>
 *   <li>Convenience: {@code /connect} — returns all connection string formats</li>
 * </ul>
 */
@ApplicationScoped
public class SqlHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(SqlHandler.class);

    private final EmulatorConfig config;
    private final SqlState state;
    private final SqlServerManager serverManager;
    private final SqlProvisioningService provisioningService;
    private final ObjectMapper mapper;

    @Inject
    public SqlHandler(EmulatorConfig config, SqlState state, SqlServerManager serverManager,
                      SqlProvisioningService provisioningService) {
        this.config = config;
        this.state = state;
        this.serverManager = serverManager;
        this.provisioningService = provisioningService;
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
    @Override public boolean canHandle(AzureRequest r) { return "sql".equals(r.serviceType()); }

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

        // ── connectionPolicies/default ────────────────────────────────────
        if (tail.matches("servers/[^/]+/connectionPolicies/default")) {
            String serverName = segment(tail, 1);
            return handleConnectionPolicy(method, request, serverName);
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

        return Response.status(Response.Status.NOT_FOUND)
            .entity(Map.of("error", "Unknown SQL path: " + tail))
            .build();
    }

    // ── checkNameAvailability ─────────────────────────────────────────────────

    private Response handleCheckNameAvailability(AzureRequest request) {
        try {
            JsonNode body = readBody(request.bodyStream());
            String name = body.path("name").asText();
            boolean available = !state.serverExists(name);
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
            case "DELETE" -> deleteServer(serverName);
            default       -> methodNotAllowed();
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
            String login     = props.path("administratorLogin").asText();
            String password  = props.path("administratorLoginPassword").asText();

            if (login.isBlank()) {
                return badRequest("administratorLogin is required");
            }
            if (password.isBlank()) {
                return badRequest("administratorLoginPassword is required");
            }

            Map<String, String> tags = parseTags(body.path("tags"));
            String sub = extractSubscriptionId(request.resourcePath());
            String rg  = extractResourceGroup(request.resourcePath());

            Optional<SqlState.SqlServerEntry> current = state.getServer(serverName);
            boolean isNew = current.isEmpty();

            if (config.services().sql().dataPlaneProvider() == NONE) {
                SqlState.SqlServerEntry ready = desiredEntry(current, serverName, sub, rg,
                    location, login, password, tags, "Ready");
                state.putServer(ready);
                if (!state.databaseExists(serverName, "master")) {
                    state.putDatabase(serverName, SqlState.SqlDatabaseEntry.master(serverName));
                }
                return Response.status(isNew ? 201 : 200).entity(serverResponse(ready)).build();
            }

            serverManager.requireEulaAccepted();
            if (current.isPresent() && "Creating".equals(current.get().provisioningState())) {
                if (!equivalent(current.get(), sub, rg, location, login, password, tags)) {
                    return ArmErrors.error(409, "ConflictingServerOperation",
                        "A conflicting create or update operation is in progress for SQL server '"
                            + serverName + "'.");
                }
                Optional<SqlProvisioningService.SqlProvisioningOperation> active =
                    provisioningService.activeOperation(serverName);
                if (active.isPresent()) {
                    return accepted(request, active.get(), current.get());
                }
                current = state.getServer(serverName);
            }

            if (current.isPresent() && "Ready".equals(current.get().provisioningState())) {
                SqlState.SqlServerEntry updated = desiredEntry(current, serverName, sub, rg,
                    location, login, password, tags, "Ready");
                state.putServer(updated);
                return Response.ok(serverResponse(updated)).build();
            }

            SqlState.SqlServerEntry desired = desiredEntry(current, serverName, sub, rg,
                location, login, password, tags, "Creating");
            state.putServer(desired);
            SqlProvisioningService.SqlProvisioningOperation operation =
                provisioningService.begin(desired);
            return accepted(request, operation, desired);

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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", operation.id());
        body.put("status", operation.status());
        body.put("startTime", operation.startTime());
        if (operation.endTime() != null) {
            body.put("endTime", operation.endTime());
        }
        if (operation.errorCode() != null) {
            body.put("error", Map.of(
                "code", operation.errorCode(),
                "message", operation.errorMessage()));
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
        String host = request.headers().getHeaderString("Host");
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
            Map<String, String> tags, String provisioningState) {
        Map<String, SqlState.SqlDatabaseEntry> databases = current
            .map(SqlState.SqlServerEntry::databases)
            .orElseGet(java.util.concurrent.ConcurrentHashMap::new);
        Map<String, SqlState.SqlFirewallRule> firewallRules = current
            .map(SqlState.SqlServerEntry::firewallRules)
            .orElseGet(java.util.concurrent.ConcurrentHashMap::new);
        Instant createdAt = current.map(SqlState.SqlServerEntry::createdAt).orElseGet(Instant::now);
        String containerId = current.map(SqlState.SqlServerEntry::containerId).orElse(null);
        int hostPort = current.map(SqlState.SqlServerEntry::hostPort).orElse(0);
        String host = current.map(SqlState.SqlServerEntry::host).orElse("localhost");
        return new SqlState.SqlServerEntry(serverName, subscriptionId, resourceGroup, location,
            login, password, containerId, hostPort, host, provisioningState, null, null,
            tags, databases, firewallRules, createdAt);
    }

    private static boolean equivalent(SqlState.SqlServerEntry current, String subscriptionId,
                                      String resourceGroup, String location, String login,
                                      String password, Map<String, String> tags) {
        return current.subscriptionId().equalsIgnoreCase(subscriptionId)
            && current.resourceGroupName().equalsIgnoreCase(resourceGroup)
            && current.location().equalsIgnoreCase(location)
            && current.administratorLogin().equals(login)
            && current.administratorLoginPassword().equals(password)
            && current.tags().equals(tags);
    }

    private Response getServer(String serverName) {
        return state.getServer(serverName)
            .map(s -> Response.ok(serverResponse(s)).build())
            .orElse(notFound("Server '" + serverName + "' not found"));
    }

    private Response deleteServer(String serverName) {
        if (!state.serverExists(serverName)) {
            return notFound("Server '" + serverName + "' not found");
        }
        provisioningService.cancel(serverName);
        Optional<SqlState.SqlServerEntry> entry = state.getServer(serverName);
        state.removeServer(serverName);
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
        String rg  = extractResourceGroup(request.resourcePath());
        List<SqlState.SqlServerEntry> servers = rg.equals("default")
            ? state.listServersBySubscription(sub)
            : state.listServersByResourceGroup(sub, rg);
        List<Map<String, Object>> value = servers.stream().map(this::serverResponse).toList();
        return Response.ok(Map.of("value", value)).build();
    }

    // ── Databases ─────────────────────────────────────────────────────────────

    private Response handleDatabase(String method, AzureRequest request,
                                     String serverName, String dbName) {
        return switch (method) {
            case "PUT"    -> createOrUpdateDatabase(request, serverName, dbName);
            case "GET"    -> getDatabase(serverName, dbName);
            case "DELETE" -> deleteDatabase(serverName, dbName);
            default       -> methodNotAllowed();
        };
    }

    private Response createOrUpdateDatabase(AzureRequest request, String serverName, String dbName) {
        Optional<SqlState.SqlServerEntry> serverOpt = state.getServer(serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");

        try {
            JsonNode body    = readBody(request.bodyStream());
            JsonNode props   = body.path("properties");
            String collation = props.path("collation").asText("");
            String edition   = props.path("edition").asText("");
            String sku       = body.path("sku").path("name").asText("");

            boolean isNew = !state.databaseExists(serverName, dbName);

            // The emulator tracks the database in state only.
            // Actual CREATE DATABASE is the responsibility of the application
            // (Flyway, Liquibase, EF Core, etc.) using the JDBC URL from /connect.
            SqlState.SqlDatabaseEntry db = SqlState.SqlDatabaseEntry.create(
                dbName, serverName, collation, edition, sku);
            state.putDatabase(serverName, db);

            return Response.status(isNew ? 201 : 200)
                .entity(databaseResponse(db, serverOpt.get()))
                .build();

        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "Error creating database %s on server %s", dbName, serverName);
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
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
        // Remove from state only — actual DROP DATABASE is the application's responsibility.
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
        return methodNotAllowed();
    }

    // ── Connection policy ─────────────────────────────────────────────────────

    private Response handleConnectionPolicy(String method, AzureRequest request, String serverName) {
        if (!state.serverExists(serverName))
            return notFound("Server '" + serverName + "' not found");
        // We always return Default, regardless of what is PUT
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", serverArmId(request.resourcePath(), serverName) + "/connectionPolicies/default");
        resp.put("name", "default");
        resp.put("type", "Microsoft.Sql/servers/connectionPolicies");
        resp.put("kind", "v12.0");
        resp.put("properties", Map.of("connectionType", "Default"));
        return Response.ok(resp).build();
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
        props.put("minimalTlsVersion", "None");
        props.put("publicNetworkAccess", "Enabled");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", s.armId());
        resp.put("name", s.serverName());
        resp.put("type", "Microsoft.Sql/servers");
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
        props.put("requestedServiceObjectiveName", db.sku());
        props.put("maxSizeBytes", "1073741824");

        String dbArmId = server.armId() + "/databases/" + db.databaseName();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", dbArmId);
        resp.put("name", db.databaseName());
        resp.put("type", "Microsoft.Sql/servers/databases");
        resp.put("location", server.location());
        resp.put("kind", "v12.0,user");
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
        int idx = fullPath.indexOf("/providers/Microsoft.Sql/");
        if (idx >= 0) return fullPath.substring(idx + "/providers/Microsoft.Sql/".length());
        // Convenience path: already relative (e.g. "servers/myserver/databases/mydb")
        return fullPath;
    }

    private static String extractSubscriptionId(String fullPath) {
        return ArmPaths.segmentAfter(fullPath, "subscriptions", "default");
    }

    private static String extractResourceGroup(String fullPath) {
        return ArmPaths.resourceGroup(fullPath, "default");
    }

    private static String serverArmId(String fullPath, String serverName) {
        String sub = extractSubscriptionId(fullPath);
        String rg  = extractResourceGroup(fullPath);
        return String.format("/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Sql/servers/%s",
            sub, rg, serverName);
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
                "message", "Azure SQL Database service is disabled on this emulator."))).build();
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
    public void clear() {
        provisioningService.clear();
        state.listServers().forEach(entry -> {
            try { serverManager.stopServer(entry); } catch (Exception e) {
                LOG.warnf(e, "Error stopping SQL container during reset: server=%s", entry.serverName());
            }
        });
        state.clear();
    }
}
