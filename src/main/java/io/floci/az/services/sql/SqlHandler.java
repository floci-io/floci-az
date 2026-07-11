package io.floci.az.services.sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    @Inject EmulatorConfig config;
    @Inject SqlState       state;
    @Inject SqlServerManager serverManager;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Guards concurrent server-start operations (per server name). */
    private final ConcurrentHashMap<String, Object> startLocks = new ConcurrentHashMap<>();

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

    private Response createOrUpdateServer(AzureRequest request, String serverName) {
        if (!config.services().sql().enabled()) return serviceDisabled();

        try {
            JsonNode body = readBody(request.bodyStream());
            String location  = body.path("location").asText("eastus");
            JsonNode props   = body.path("properties");
            String login     = props.path("administratorLogin").asText();
            String password  = props.path("administratorLoginPassword").asText();

            if (login.isBlank()) return badRequest("administratorLogin is required");
            if (password.isBlank()) return badRequest("administratorLoginPassword is required");

            Map<String, String> tags = parseTags(body.path("tags"));
            String sub = extractSubscriptionId(request.resourcePath());
            String rg  = extractResourceGroup(request.resourcePath());

            // Upsert — if server exists, update metadata but do NOT restart container
            boolean isNew = !state.serverExists(serverName);
            SqlState.SqlServerEntry entry;
            if (isNew) {
                var databases     = new java.util.concurrent.ConcurrentHashMap<String, SqlState.SqlDatabaseEntry>();
                var firewallRules = new java.util.concurrent.ConcurrentHashMap<String, SqlState.SqlFirewallRule>();
                entry = new SqlState.SqlServerEntry(
                    serverName, sub, rg, location, login, password,
                    null, 0, "localhost", tags, databases, firewallRules, Instant.now());
                state.putServer(entry);

                if (config.services().sql().mocked()) {
                    // Mocked mode: no SQL Server container (no Docker, no EULA). The server is
                    // pure ARM state and reports state=Ready; the data plane is unavailable.
                    state.putDatabase(serverName, SqlState.SqlDatabaseEntry.master(serverName));
                    return Response.status(201).entity(serverResponse(entry)).build();
                }

                // Start container (may take 15-30s)
                try {
                    Object lock = startLocks.computeIfAbsent(serverName.toLowerCase(), k -> new Object());
                    synchronized (lock) {
                        // Re-check after acquiring lock — another thread may have started it
                        Optional<SqlState.SqlServerEntry> current = state.getServer(serverName);
                        if (current.isPresent() && current.get().containerId() != null) {
                            entry = current.get();
                        } else {
                            entry = serverManager.startServer(entry);
                            // Auto-create master database in state
                            state.putServer(entry);
                            state.putDatabase(serverName, SqlState.SqlDatabaseEntry.master(serverName));
                        }
                    }
                } catch (SqlServerManager.EulaNotAcceptedException e) {
                    state.removeServer(serverName);
                    return Response.status(503)
                        .entity(Map.of("error", "EulaNotAccepted", "message", e.getMessage()))
                        .build();
                } catch (Exception e) {
                    state.removeServer(serverName);
                    LOG.errorf(e, "Failed to start SQL Server container for server=%s", serverName);
                    return Response.status(500)
                        .entity(Map.of("error", "ContainerStartFailed", "message", e.getMessage()))
                        .build();
                }
            } else {
                entry = state.getServer(serverName).get();
                // Update mutable fields
                state.putServer(new SqlState.SqlServerEntry(
                    serverName, sub, rg, location, login,
                    password.isBlank() ? entry.administratorLoginPassword() : password,
                    entry.containerId(), entry.hostPort(), entry.host(), tags,
                    entry.databases(), entry.firewallRules(), entry.createdAt()));
                entry = state.getServer(serverName).get();
            }

            return Response.status(isNew ? 201 : 200)
                .entity(serverResponse(entry))
                .build();

        } catch (Exception e) {
            LOG.errorf(e, "Error creating SQL server %s", serverName);
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    private Response getServer(String serverName) {
        return state.getServer(serverName)
            .map(s -> Response.ok(serverResponse(s)).build())
            .orElse(notFound("Server '" + serverName + "' not found"));
    }

    private Response deleteServer(String serverName) {
        Optional<SqlState.SqlServerEntry> entry = state.getServer(serverName);
        if (entry.isEmpty()) return notFound("Server '" + serverName + "' not found");
        state.removeServer(serverName);
        try { serverManager.stopServer(entry.get()); } catch (Exception e) {
            LOG.warnf(e, "Error stopping SQL container for server %s", serverName);
        }
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
        return state.getServer(serverName)
            .map(s -> {
                SqlConnectionInfo info = SqlConnectionInfo.of(
                    s.fullyQualifiedDomainName(), s.hostPort(),
                    s.administratorLogin(), "***", null);
                return Response.ok(connectResponse(s, info, null)).build();
            })
            .orElse(notFound("Server '" + serverName + "' not found"));
    }

    private Response handleDatabaseConnect(String serverName, String dbName) {
        Optional<SqlState.SqlServerEntry> serverOpt = state.getServer(serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");
        Optional<SqlState.SqlDatabaseEntry> dbOpt = state.getDatabase(serverName, dbName);
        if (dbOpt.isEmpty()) return notFound("Database '" + dbName + "' not found");

        SqlState.SqlServerEntry server = serverOpt.get();
        SqlConnectionInfo info = SqlConnectionInfo.of(
            server.fullyQualifiedDomainName(), server.hostPort(),
            server.administratorLogin(), "***", dbName);
        return Response.ok(connectResponse(server, info, dbName)).build();
    }

    // ── Response builders ─────────────────────────────────────────────────────

    private Map<String, Object> serverResponse(SqlState.SqlServerEntry s) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("administratorLogin", s.administratorLogin());
        props.put("version", "12.0");
        props.put("state", (config.services().sql().mocked() || s.containerId() != null) ? "Ready" : "Creating");
        props.put("fullyQualifiedDomainName", s.fullyQualifiedDomainName());
        props.put("minimalTlsVersion", "None");
        props.put("publicNetworkAccess", "Enabled");
        // floci-az convenience — not in the real spec. This is the reachable port (the published
        // host port for host networking, or the in-network container port when floci-az runs in a
        // container). Prefer the /connect endpoint, which returns the matching reachable host.
        if (s.hostPort() > 0) props.put("localPort", s.hostPort());

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

    private Map<String, Object> connectResponse(SqlState.SqlServerEntry server,
                                                 SqlConnectionInfo info, String database) {
        SqlConnectionInfo real = SqlConnectionInfo.of(
            server.fullyQualifiedDomainName(), server.hostPort(),
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
        return resp;
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

    /**
     * Stops all running SQL Server containers and wipes state.
     * Used by {@code POST /_admin/reset} for test isolation.
     */
    public void clearAll() {
        state.listServers().forEach(entry -> {
            try { serverManager.stopServer(entry); } catch (Exception e) {
                LOG.warnf(e, "Error stopping SQL container during reset: server=%s", entry.serverName());
            }
        });
        state.clearAll();
        startLocks.clear();
    }
}
