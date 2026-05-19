package io.floci.az.services.cosmos.table;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * In-memory Azure Cosmos DB Table API handler.
 *
 * <p>Implements the Azure Table Storage REST API subset required by the Azure Data Tables SDK:
 * <ul>
 *   <li>Table management — GET/POST/DELETE /Tables, GET/DELETE /Tables('name')</li>
 *   <li>Entity CRUD — GET/POST /table, PUT/PATCH/MERGE /table(pk,rk), DELETE /table(pk,rk)</li>
 *   <li>Query — GET /table()?$filter=…&amp;$top=n&amp;$select=f1,f2</li>
 * </ul>
 *
 * <p>All state is held in memory via {@link ConcurrentHashMap}; no Docker container is required.
 * This handler is invoked by {@link io.floci.az.services.cosmos.engine.CosmosEngineHandler}
 * for non-control-plane requests on the {@code cosmos-table} service type.
 */
@ApplicationScoped
public class CosmosTableApiHandler {

    private static final Logger LOG = Logger.getLogger(CosmosTableApiHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    // "account|tableName" → table display name
    private final ConcurrentHashMap<String, String> tables = new ConcurrentHashMap<>();

    // "account|tableName|partitionKey|rowKey" → entity property map (includes PartitionKey, RowKey, etc.)
    private final ConcurrentHashMap<String, Map<String, Object>> entities = new ConcurrentHashMap<>();

    private final CosmosTableODataFilter filter = new CosmosTableODataFilter();

    // Entity key pattern: tableName(PartitionKey='pk',RowKey='rk') or tableName()
    private static final Pattern ENTITY_PATH_PATTERN = Pattern.compile(
            "([^(]+)\\((?:PartitionKey='([^']*)',\\s*RowKey='([^']*)')?\\)");
    // Tables('name')
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile(
            "Tables\\('([^']*)'\\)");

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public Response handle(AzureRequest request) {
        String path   = request.resourcePath();
        String method = request.method().toUpperCase();
        String account = request.accountName();

        LOG.debugf("Table API [%s] %s account=%s", method, path, account);

        // --- Tables resource ---
        if ("Tables".equals(path) || path.startsWith("Tables(")) {
            return handleTables(request, method, account, path);
        }

        // --- Entity resource: tableName(...) or tableName ---
        Matcher entityMatcher = ENTITY_PATH_PATTERN.matcher(path);
        if (entityMatcher.matches()) {
            String tableName    = entityMatcher.group(1);
            String partitionKey = entityMatcher.group(2); // null = query all
            String rowKey       = entityMatcher.group(3); // null = query all

            if (partitionKey != null) {
                // Specific entity
                return handleEntity(request, method, account, tableName, partitionKey, rowKey);
            } else {
                // tableName() — query
                return queryEntities(request, account, tableName);
            }
        }

        // Bare tableName (no parentheses) — INSERT
        if ("POST".equals(method)) {
            return insertEntity(request, account, path);
        }

        return notImplemented("Unknown Table path: " + path);
    }

    // -----------------------------------------------------------------------
    // Table management
    // -----------------------------------------------------------------------

    private Response handleTables(AzureRequest request, String method, String account, String path) {
        // DELETE /Tables('name')
        Matcher tblMatcher = TABLE_NAME_PATTERN.matcher(path);
        if (tblMatcher.matches()) {
            if ("DELETE".equals(method)) return deleteTable(account, tblMatcher.group(1));
            if ("GET".equals(method))    return getTableMeta(account, tblMatcher.group(1));
        }

        return switch (method) {
            case "GET"  -> listTables(account);
            case "POST" -> createTable(request, account);
            default     -> notImplemented("Tables " + method);
        };
    }

    private Response listTables(String account) {
        String prefix = account + "|";
        List<Map<String, Object>> items = tables.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(e -> tableMetaObject(e.getValue()))
                .collect(Collectors.toList());
        return Response.ok(Map.of("value", items)).build();
    }

    private Response createTable(AzureRequest request, String account) {
        try {
            Map<String, Object> body = readBody(request);
            String tableName = (String) body.get("TableName");
            if (tableName == null || tableName.isBlank()) {
                return badRequest("Missing TableName");
            }
            String key = account + "|" + tableName;
            if (tables.putIfAbsent(key, tableName) != null) {
                // Already exists → 409 Conflict
                return Response.status(409)
                        .entity(Map.of("odata.error", Map.of(
                                "code", "TableAlreadyExists",
                                "message", Map.of("lang", "en-US", "value", "Table already exists."))))
                        .build();
            }
            LOG.infof("Table created: account=%s table=%s", account, tableName);
            return Response.status(201).entity(tableMetaObject(tableName)).build();
        } catch (IOException e) {
            return badRequest("Invalid body: " + e.getMessage());
        }
    }

    private Response getTableMeta(String account, String tableName) {
        String key = account + "|" + tableName;
        if (!tables.containsKey(key)) return tableNotFound(tableName);
        return Response.ok(tableMetaObject(tableName)).build();
    }

    private Response deleteTable(String account, String tableName) {
        String key = account + "|" + tableName;
        if (tables.remove(key) == null) return tableNotFound(tableName);

        // Remove all entities in this table
        String entityPrefix = account + "|" + tableName + "|";
        entities.keySet().removeIf(k -> k.startsWith(entityPrefix));
        LOG.infof("Table deleted: account=%s table=%s", account, tableName);
        return Response.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Entity operations
    // -----------------------------------------------------------------------

    private Response handleEntity(AzureRequest request, String method, String account,
                                   String tableName, String partitionKey, String rowKey) {
        return switch (method) {
            case "GET"             -> getEntity(account, tableName, partitionKey, rowKey);
            case "PUT"             -> upsertEntity(request, account, tableName, partitionKey, rowKey, false);
            case "PATCH", "MERGE"  -> upsertEntity(request, account, tableName, partitionKey, rowKey, true);
            case "DELETE"          -> deleteEntity(account, tableName, partitionKey, rowKey);
            default                -> notImplemented("Entity " + method);
        };
    }

    private Response getEntity(String account, String tableName, String partitionKey, String rowKey) {
        String key = entityKey(account, tableName, partitionKey, rowKey);
        Map<String, Object> entity = entities.get(key);
        if (entity == null) return entityNotFound(partitionKey, rowKey);
        return Response.ok(withMeta(entity)).build();
    }

    private Response insertEntity(AzureRequest request, String account, String tableName) {
        try {
            Map<String, Object> body = readBody(request);
            String pk = (String) body.get("PartitionKey");
            String rk = (String) body.get("RowKey");
            if (pk == null || rk == null) return badRequest("PartitionKey and RowKey are required");

            ensureTableExists(account, tableName);

            String key = entityKey(account, tableName, pk, rk);
            Map<String, Object> entity = buildEntity(pk, rk, body);

            if (entities.putIfAbsent(key, entity) != null) {
                return Response.status(409)
                        .entity(errorBody("EntityAlreadyExists", "The specified entity already exists."))
                        .build();
            }
            return Response.status(201).entity(withMeta(entity)).build();
        } catch (IOException e) {
            return badRequest("Invalid body: " + e.getMessage());
        }
    }

    private Response upsertEntity(AzureRequest request, String account, String tableName,
                                   String partitionKey, String rowKey, boolean merge) {
        try {
            Map<String, Object> body = readBody(request);
            ensureTableExists(account, tableName);
            String key = entityKey(account, tableName, partitionKey, rowKey);

            if (merge) {
                // Merge: keep existing fields, overlay new ones
                entities.merge(key, buildEntity(partitionKey, rowKey, body), (existing, incoming) -> {
                    Map<String, Object> merged = new LinkedHashMap<>(existing);
                    merged.putAll(incoming);
                    return merged;
                });
            } else {
                // Replace: full overwrite
                entities.put(key, buildEntity(partitionKey, rowKey, body));
            }
            return Response.noContent().build();
        } catch (IOException e) {
            return badRequest("Invalid body: " + e.getMessage());
        }
    }

    private Response deleteEntity(String account, String tableName, String partitionKey, String rowKey) {
        String key = entityKey(account, tableName, partitionKey, rowKey);
        if (entities.remove(key) == null) return entityNotFound(partitionKey, rowKey);
        return Response.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Query — GET /tableName()?$filter=…&$top=n&$select=f1,f2
    // -----------------------------------------------------------------------

    private Response queryEntities(AzureRequest request, String account, String tableName) {
        String filterExpr = request.queryParams().getOrDefault("$filter", null);
        String topParam   = request.queryParams().getOrDefault("$top", null);
        String selectParam = request.queryParams().getOrDefault("$select", null);

        String entityPrefix = account + "|" + tableName + "|";

        List<Map<String, Object>> results = entities.entrySet().stream()
                .filter(e -> e.getKey().startsWith(entityPrefix))
                .map(e -> e.getValue())
                .filter(e -> filter.matches(e, filterExpr))
                .map(this::withMeta)
                .collect(Collectors.toCollection(ArrayList::new));

        // $top
        if (topParam != null) {
            try {
                int top = Integer.parseInt(topParam);
                results = results.stream().limit(top).collect(Collectors.toList());
            } catch (NumberFormatException ignored) {}
        }

        // $select
        if (selectParam != null && !selectParam.isBlank()) {
            Set<String> fields = Arrays.stream(selectParam.split(","))
                    .map(String::trim).collect(Collectors.toSet());
            // Always include key fields
            fields.add("PartitionKey");
            fields.add("RowKey");
            fields.add("Timestamp");
            Set<String> finalFields = fields;
            results = results.stream().map(e -> {
                Map<String, Object> projected = new LinkedHashMap<>();
                e.forEach((k, v) -> {
                    if (finalFields.contains(k) || finalFields.contains(k.replace("@odata.type", "")))
                        projected.put(k, v);
                });
                return projected;
            }).collect(Collectors.toList());
        }

        return Response.ok(Map.of("value", results)).build();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String entityKey(String account, String tableName, String pk, String rk) {
        return account + "|" + tableName + "|" + pk + "|" + rk;
    }

    private Map<String, Object> buildEntity(String pk, String rk, Map<String, Object> body) {
        Map<String, Object> entity = new LinkedHashMap<>(body);
        entity.put("PartitionKey", pk);
        entity.put("RowKey", rk);
        entity.put("Timestamp", TS_FMT.format(Instant.now()));
        // Remove internal Azure meta-props sent by the SDK; we re-add them on reads
        entity.remove("odata.etag");
        entity.remove("odata.metadata");
        entity.remove("odata.editLink");
        entity.remove("odata.id");
        entity.remove("odata.type");
        return entity;
    }

    /** Add server-side OData metadata fields to an entity before returning it. */
    private Map<String, Object> withMeta(Map<String, Object> entity) {
        Map<String, Object> result = new LinkedHashMap<>(entity);
        String ts = (String) result.getOrDefault("Timestamp", TS_FMT.format(Instant.now()));
        result.put("Timestamp", ts);
        result.put("odata.etag", "W/\"datetime'%s'\"".formatted(ts));
        return result;
    }

    private Map<String, Object> tableMetaObject(String tableName) {
        return Map.of("TableName", tableName);
    }

    /** If the table doesn't exist yet, auto-create it (mirrors Azurite behavior). */
    private void ensureTableExists(String account, String tableName) {
        tables.putIfAbsent(account + "|" + tableName, tableName);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(AzureRequest request) throws IOException {
        if (request.bodyStream() == null) return new LinkedHashMap<>();
        byte[] raw = request.bodyStream().readAllBytes();
        if (raw.length == 0) return new LinkedHashMap<>();
        return MAPPER.readValue(raw, new TypeReference<>() {});
    }

    private Map<String, Object> errorBody(String code, String message) {
        return Map.of("odata.error", Map.of(
                "code", code,
                "message", Map.of("lang", "en-US", "value", message)));
    }

    private Response tableNotFound(String tableName) {
        return Response.status(404)
                .entity(errorBody("ResourceNotFound", "Table '" + tableName + "' not found."))
                .build();
    }

    private Response entityNotFound(String pk, String rk) {
        return Response.status(404)
                .entity(errorBody("ResourceNotFound",
                        "Entity PartitionKey='" + pk + "' RowKey='" + rk + "' not found."))
                .build();
    }

    private Response badRequest(String msg) {
        return Response.status(400).entity(errorBody("InvalidInput", msg)).build();
    }

    private Response notImplemented(String msg) {
        return Response.status(501).entity(errorBody("NotImplemented", msg)).build();
    }
}
