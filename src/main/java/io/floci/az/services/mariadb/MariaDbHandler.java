package io.floci.az.services.mariadb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
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
public class MariaDbHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(MariaDbHandler.class);

    private static final String NS = "/providers/Microsoft.DBforMariaDB/";

    @Inject EmulatorConfig config;
    @Inject MariaDbState state;
    @Inject MariaDbServerManager serverManager;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Object> startLocks = new ConcurrentHashMap<>();

    @Override public String getServiceType()           { return "mariadb"; }
    @Override public boolean canHandle(AzureRequest r) { return "mariadb".equals(r.serviceType()); }

    @Override
    public Response handle(AzureRequest request) {
        String tail = extractMariaDbPath(request.resourcePath());
        String method = request.method();

        LOG.debugf("MariaDbHandler: %s %s → tail=%s", method, request.resourcePath(), tail);

        if (tail.endsWith("checkNameAvailability") && "POST".equals(method)) {
            return handleCheckNameAvailability(request);
        }

        if (tail.matches("servers/[^/]+/connect")) {
            return handleServerConnect(segment(tail, 1));
        }

        if (tail.matches("servers/[^/]+/configurations/[^/]+")) {
            return handleConfiguration(method, request, segment(tail, 1), segment(tail, 3));
        }
        if (tail.matches("servers/[^/]+/configurations")) {
            return handleConfigurationList(segment(tail, 1));
        }

        if (tail.matches("servers/[^/]+/firewallRules/[^/]+")) {
            return handleFirewallRule(method, request, segment(tail, 1), segment(tail, 3));
        }
        if (tail.matches("servers/[^/]+/firewallRules")) {
            return handleFirewallRuleList(method, segment(tail, 1));
        }

        if (tail.matches("servers/[^/]+/databases/[^/]+")) {
            return handleDatabase(method, request, segment(tail, 1), segment(tail, 3));
        }
        if (tail.matches("servers/[^/]+/databases")) {
            return handleDatabaseList(segment(tail, 1));
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

    private Response handleCheckNameAvailability(AzureRequest request) {
        try {
            JsonNode body = readBody(request.bodyStream());
            String name = body.path("name").asText();
            boolean available = !state.serverExists(name);
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
            case "GET"    -> getServer(serverName);
            case "DELETE" -> deleteServer(serverName);
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

            boolean isNew = !state.serverExists(serverName);

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
                state.putServer(entry);

                if (config.services().mariaDb().mocked()) {
                    return Response.status(201).entity(serverResponse(entry)).build();
                }

                try {
                    Object lock = startLocks.computeIfAbsent(serverName.toLowerCase(), k -> new Object());
                    synchronized (lock) {
                        Optional<MariaDbState.ServerEntry> current = state.getServer(serverName);
                        if (current.isPresent() && current.get().containerId() != null) {
                            entry = current.get();
                        } else {
                            entry = serverManager.startServer(entry);
                            state.putServer(entry);
                        }
                    }
                } catch (Exception e) {
                    state.removeServer(serverName);
                    LOG.errorf(e, "Failed to start MariaDB container for server=%s", serverName);
                    return Response.status(500)
                        .entity(Map.of("error", "ContainerStartFailed", "message", String.valueOf(e.getMessage())))
                        .build();
                }
                return Response.status(201).entity(serverResponse(entry)).build();
            }

            MariaDbState.ServerEntry existing = state.getServer(serverName).get();
            String password = props.path("administratorLoginPassword").asText("");
            String version  = props.path("version").asText(existing.version());
            int storageMB   = props.path("storageProfile").path("storageMB").asInt(existing.storageMB());
            Map<String, String> mergedTags = body.has("tags") ? tags : existing.tags();

            MariaDbState.ServerEntry updated = new MariaDbState.ServerEntry(
                serverName, existing.subscriptionId(), existing.resourceGroupName(),
                existing.location(), version, existing.administratorLogin(),
                password.isBlank() ? existing.administratorLoginPassword() : password,
                storageMB,
                existing.containerId(), existing.hostPort(), existing.host(), mergedTags,
                existing.databases(), existing.firewallRules(), existing.configurations(),
                existing.createdAt());
            state.putServer(updated);
            return Response.status(200).entity(serverResponse(updated)).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error creating MariaDB server %s", serverName);
            return Response.status(500).entity(Map.of("error", String.valueOf(e.getMessage()))).build();
        }
    }

    private Response getServer(String serverName) {
        return state.getServer(serverName)
            .map(s -> Response.ok(serverResponse(s)).build())
            .orElse(notFound("Server '" + serverName + "' not found"));
    }

    private Response deleteServer(String serverName) {
        Optional<MariaDbState.ServerEntry> entry = state.getServer(serverName);
        if (entry.isEmpty()) return notFound("Server '" + serverName + "' not found");
        state.removeServer(serverName);
        try { serverManager.stopServer(entry.get()); } catch (Exception e) {
            LOG.warnf(e, "Error stopping MariaDB container for server %s", serverName);
        }
        return Response.status(204).build();
    }

    private Response handleServerList(AzureRequest request) {
        String sub = extractSubscriptionId(request.resourcePath());
        String rg  = extractResourceGroup(request.resourcePath());
        List<MariaDbState.ServerEntry> servers = rg.equals("default")
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
        Optional<MariaDbState.ServerEntry> serverOpt = state.getServer(serverName);
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

    private Response getDatabase(String serverName, String dbName) {
        Optional<MariaDbState.ServerEntry> serverOpt = state.getServer(serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");
        return state.getDatabase(serverName, dbName)
            .map(db -> Response.ok(databaseResponse(db, serverOpt.get())).build())
            .orElse(notFound("Database '" + dbName + "' not found on server '" + serverName + "'"));
    }

    private Response deleteDatabase(String serverName, String dbName) {
        Optional<MariaDbState.ServerEntry> serverOpt = state.getServer(serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");
        if (!state.databaseExists(serverName, dbName))
            return notFound("Database '" + dbName + "' not found");
        state.removeDatabase(serverName, dbName);
        return Response.status(204).build();
    }

    private Response handleDatabaseList(String serverName) {
        Optional<MariaDbState.ServerEntry> serverOpt = state.getServer(serverName);
        if (serverOpt.isEmpty()) return notFound("Server '" + serverName + "' not found");
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

    private Response handleFirewallRuleList(String method, String serverName) {
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

    // ── Configurations ──────────────────────────────────────────────────────────

    private Response handleConfiguration(String method, AzureRequest request,
                                         String serverName, String cfgName) {
        if (!state.serverExists(serverName))
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

    private Response handleConfigurationList(String serverName) {
        if (!state.serverExists(serverName))
            return notFound("Server '" + serverName + "' not found");
        List<Map<String, Object>> value = state.listConfigurations(serverName).entrySet().stream()
            .map(e -> configurationResponse(serverName, e.getKey(), e.getValue()))
            .toList();
        return Response.ok(Map.of("value", value)).build();
    }

    // ── Convenience /connect ──────────────────────────────────────────────────

    private Response handleServerConnect(String serverName) {
        return state.getServer(serverName)
            .map(s -> {
                MariaDbConnectionInfo info = MariaDbConnectionInfo.of(
                    s.fullyQualifiedDomainName(), s.hostPort(),
                    s.administratorLogin(), s.administratorLoginPassword(), null);
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("server", s.serverName());
                resp.put("host", info.host());
                resp.put("port", info.port());
                resp.put("jdbcUrl", info.jdbcUrl());
                resp.put("uri", info.uri());
                resp.put("mysql", info.mysql());
                resp.put("dotNet", info.dotNet());
                return Response.ok(resp).build();
            })
            .orElse(notFound("Server '" + serverName + "' not found"));
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
        resp.put("type", "Microsoft.DBforMariaDB/servers");
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
        int idx = fullPath.indexOf(NS);
        if (idx >= 0) return fullPath.substring(idx + NS.length());
        return fullPath;
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

    public void clearAll() {
        state.listServers().forEach(entry -> {
            try { serverManager.stopServer(entry); } catch (Exception e) {
                LOG.warnf(e, "Error stopping MariaDB container during reset: server=%s", entry.serverName());
            }
        });
        state.clearAll();
        startLocks.clear();
    }
}
