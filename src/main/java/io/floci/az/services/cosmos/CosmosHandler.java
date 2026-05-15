package io.floci.az.services.cosmos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cosmos DB SQL API emulator.
 *
 * Routing suffix: {@code {account}-cosmos}
 *
 * Supported resources:
 *   Databases  — GET/POST /dbs, GET/DELETE /dbs/{dbId}
 *   Containers — GET/POST /dbs/{dbId}/colls, GET/DELETE /dbs/{dbId}/colls/{collId}
 *   Documents  — GET/POST/PUT/DELETE /dbs/{dbId}/colls/{collId}/docs[/{docId}]
 *   Queries    — POST /dbs/{dbId}/colls/{collId}/docs with x-ms-documentdb-isquery: True
 */
@ApplicationScoped
public class CosmosHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(CosmosHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Storage key separators — '|' is not a valid Cosmos DB resource-ID character
    private static final String K_DB   = "|db|";
    private static final String K_COLL = "|coll|";
    private static final String K_DOC  = "|doc|";

    private final StorageBackend<String, StoredObject> store;
    private final CosmosQueryEngine queryEngine = new CosmosQueryEngine();

    @Inject
    public CosmosHandler(StorageFactory factory) {
        this.store = factory.create("cosmos");
    }

    @Override public String getServiceType()              { return "cosmos"; }
    @Override public boolean canHandle(AzureRequest req)  { return "cosmos".equals(req.serviceType()); }

    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();
        LOG.debugf("Cosmos %s /%s", req.method(), path);

        String[] segs = path.isEmpty() ? new String[0] : path.split("/");
        if (segs.length == 0) {
            // GET / — database account info (called by SDKs on client init)
            return "GET".equalsIgnoreCase(req.method()) ? getAccountInfo(req) : notImplemented();
        }
        if (!"dbs".equals(segs[0])) return notImplemented();
        return routeDbs(req, segs);
    }

    // -----------------------------------------------------------------------
    // Routing
    // -----------------------------------------------------------------------

    private Response routeDbs(AzureRequest req, String[] segs) {
        String m = req.method().toUpperCase();
        if (segs.length == 1) return switch (m) {
            case "GET"  -> listDatabases(req);
            case "POST" -> createDatabase(req);
            default     -> notImplemented();
        };

        String dbId = segs[1];
        if (segs.length == 2) return switch (m) {
            case "GET"    -> getDatabase(req, dbId);
            case "DELETE" -> deleteDatabase(req, dbId);
            default       -> notImplemented();
        };

        if (segs.length >= 3 && "colls".equals(segs[2])) return routeColls(req, segs, dbId);
        return notImplemented();
    }

    private Response routeColls(AzureRequest req, String[] segs, String dbId) {
        String m = req.method().toUpperCase();
        if (segs.length == 3) return switch (m) {
            case "GET"  -> listContainers(req, dbId);
            case "POST" -> createContainer(req, dbId);
            default     -> notImplemented();
        };

        String collId = segs[3];
        if (segs.length == 4) return switch (m) {
            case "GET"    -> getContainer(req, dbId, collId);
            case "DELETE" -> deleteContainer(req, dbId, collId);
            default       -> notImplemented();
        };

        if (segs.length >= 5 && "docs".equals(segs[4])) return routeDocs(req, segs, dbId, collId);
        return notImplemented();
    }

    private Response routeDocs(AzureRequest req, String[] segs, String dbId, String collId) {
        String m      = req.method().toUpperCase();
        boolean query = "True".equalsIgnoreCase(req.headers().getHeaderString("x-ms-documentdb-isquery"))
                || "application/query+json".equalsIgnoreCase(req.headers().getHeaderString("Content-Type"));

        if (segs.length == 5) return switch (m) {
            case "GET"  -> listDocuments(req, dbId, collId);
            case "POST" -> query ? queryDocuments(req, dbId, collId) : createDocument(req, dbId, collId);
            default     -> notImplemented();
        };

        String docId = segs[5];
        if (segs.length == 6) return switch (m) {
            case "GET"    -> getDocument(req, dbId, collId, docId);
            case "PUT"    -> replaceDocument(req, dbId, collId, docId);
            case "DELETE" -> deleteDocument(req, dbId, collId, docId);
            default       -> notImplemented();
        };
        return notImplemented();
    }

    // -----------------------------------------------------------------------
    // Account info  (GET /)
    // -----------------------------------------------------------------------

    private Response getAccountInfo(AzureRequest req) {
        String account = req.accountName();
        Map<String, Object> consistency = new LinkedHashMap<>();
        consistency.put("defaultConsistencyLevel", "Session");
        consistency.put("maxStalenessPrefix", 100);
        consistency.put("maxIntervalInSeconds", 5);

        // The SDK uses writableLocations[0].databaseAccountEndpoint for routing.
        // We point it back at ourselves so endpoint-discovery never leaves the emulator.
        String self = req.headers().getHeaderString("Host");
        if (self == null || self.isBlank()) self = "localhost:4577";
        String endpoint = "http://" + self + "/" + account + "-cosmos/";

        Map<String, String> location = Map.of(
            "name", "South Central US",
            "databaseAccountEndpoint", endpoint
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id",                           account);
        body.put("_rid",                         "");
        body.put("_self",                        "");
        body.put("_ts",                          Instant.now().getEpochSecond());
        body.put("databasesLink",                "dbs/");
        body.put("mediaLink",                    "media/");
        body.put("storageQuotaInMB",             10240);
        body.put("currentMediaStorageUsageInMB", 0);
        body.put("consistencyPolicy",            consistency);
        body.put("writableLocations",            List.of(location));
        body.put("readableLocations",            List.of(location));
        body.put("enableMultipleWriteLocations", false);
        body.put("userReplicationPolicy",        Map.of("asyncReplication", false, "minReplicaSetSize", 1, "maxReplicasetSize", 4));
        body.put("systemReplicationPolicy",      Map.of("minReplicaSetSize", 1, "maxReplicasetSize", 4));
        body.put("readPolicy",                   Map.of("primaryReadCoefficient", 1, "secondaryReadCoefficient", 1));
        body.put("queryEngineConfiguration",     "{}");

        try {
            return Response.ok(MAPPER.writeValueAsString(body))
                    .type("application/json")
                    .header("x-ms-request-charge", "1")
                    .header("x-ms-activity-id",    UUID.randomUUID().toString())
                    .header("x-ms-version",        "2018-12-31")
                    .build();
        } catch (JsonProcessingException e) {
            return Response.serverError().build();
        }
    }

    // -----------------------------------------------------------------------
    // Databases
    // -----------------------------------------------------------------------

    private Response listDatabases(AzureRequest req) {
        String prefix = req.accountName() + K_DB;
        List<Map<String, Object>> items = store.scan(k -> k.startsWith(prefix) && !k.substring(prefix.length()).contains("|"))
                .stream().map(this::parseData).collect(Collectors.toList());
        return listResponse("Databases", items, "");
    }

    private Response createDatabase(AzureRequest req) {
        Map<String, Object> body = parseBody(req);
        String id = (String) body.get("id");
        if (id == null || id.isBlank()) return errorResponse(400, "BadRequest", "'id' is required.");

        String key = dbKey(req.accountName(), id);
        if (store.get(key).isPresent()) return errorResponse(409, "Conflict", "Database '" + id + "' already exists.");

        Instant now  = Instant.now();
        String  rid  = newRid(4);
        String  etag = newEtag();

        Map<String, Object> db = new LinkedHashMap<>();
        db.put("id",     id);
        db.put("_rid",   rid);
        db.put("_self",  "dbs/" + id + "/");
        db.put("_etag",  quoted(etag));
        db.put("_ts",    now.getEpochSecond());
        db.put("_colls", "colls/");
        db.put("_users", "users/");

        store.put(key, stored(key, db, now, etag));
        return cosmosResponse(db, Response.Status.CREATED, etag);
    }

    private Response getDatabase(AzureRequest req, String dbId) {
        Optional<StoredObject> found = store.get(dbKey(req.accountName(), dbId));
        if (found.isEmpty()) return notFound(dbId);
        return cosmosResponse(parseData(found.get()), Response.Status.OK, found.get().etag());
    }

    private Response deleteDatabase(AzureRequest req, String dbId) {
        String key = dbKey(req.accountName(), dbId);
        if (store.get(key).isEmpty()) return notFound(dbId);

        // Cascade: remove all containers and documents under this database
        store.scan(k -> k.startsWith(req.accountName() + K_COLL + dbId + "|")).forEach(o -> store.delete(o.key()));
        store.scan(k -> k.startsWith(req.accountName() + K_DOC  + dbId + "|")).forEach(o -> store.delete(o.key()));
        store.delete(key);
        return Response.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Containers (Collections)
    // -----------------------------------------------------------------------

    private Response listContainers(AzureRequest req, String dbId) {
        if (store.get(dbKey(req.accountName(), dbId)).isEmpty()) return notFound(dbId);
        String prefix = req.accountName() + K_COLL + dbId + "|";
        List<Map<String, Object>> items = store.scan(k -> k.startsWith(prefix))
                .stream().map(this::parseData).collect(Collectors.toList());
        return listResponse("DocumentCollections", items, dbRid(req.accountName(), dbId));
    }

    private Response createContainer(AzureRequest req, String dbId) {
        if (store.get(dbKey(req.accountName(), dbId)).isEmpty()) return notFound(dbId);

        Map<String, Object> body = parseBody(req);
        String id = (String) body.get("id");
        if (id == null || id.isBlank()) return errorResponse(400, "BadRequest", "'id' is required.");

        String key = collKey(req.accountName(), dbId, id);
        if (store.get(key).isPresent()) return errorResponse(409, "Conflict", "Container '" + id + "' already exists.");

        Instant now  = Instant.now();
        String  rid  = newRid(8);
        String  etag = newEtag();

        @SuppressWarnings("unchecked")
        Map<String, Object> pk = body.get("partitionKey") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : new LinkedHashMap<>(Map.of("paths", List.of("/id"), "kind", "Hash"));
        pk.put("version", 2);

        Map<String, Object> coll = new LinkedHashMap<>();
        coll.put("id",             id);
        coll.put("_rid",           rid);
        coll.put("_self",          "dbs/" + dbId + "/colls/" + id + "/");
        coll.put("_etag",          quoted(etag));
        coll.put("_ts",            now.getEpochSecond());
        coll.put("partitionKey",   pk);
        coll.put("indexingPolicy", defaultIndexingPolicy());
        coll.put("_docs",          "docs/");
        coll.put("_sprocs",        "sprocs/");
        coll.put("_triggers",      "triggers/");
        coll.put("_udfs",          "udfs/");
        coll.put("_conflicts",     "conflicts/");

        store.put(key, stored(key, coll, now, etag));
        return cosmosResponse(coll, Response.Status.CREATED, etag);
    }

    private Response getContainer(AzureRequest req, String dbId, String collId) {
        Optional<StoredObject> found = store.get(collKey(req.accountName(), dbId, collId));
        if (found.isEmpty()) return notFound(collId);
        return cosmosResponse(parseData(found.get()), Response.Status.OK, found.get().etag());
    }

    private Response deleteContainer(AzureRequest req, String dbId, String collId) {
        String key = collKey(req.accountName(), dbId, collId);
        if (store.get(key).isEmpty()) return notFound(collId);

        store.scan(k -> k.startsWith(req.accountName() + K_DOC + dbId + "|" + collId + "|"))
                .forEach(o -> store.delete(o.key()));
        store.delete(key);
        return Response.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Documents
    // -----------------------------------------------------------------------

    private Response listDocuments(AzureRequest req, String dbId, String collId) {
        if (store.get(collKey(req.accountName(), dbId, collId)).isEmpty()) return notFound(collId);
        String prefix = req.accountName() + K_DOC + dbId + "|" + collId + "|";
        List<Map<String, Object>> items = store.scan(k -> k.startsWith(prefix))
                .stream().map(this::parseData).collect(Collectors.toList());
        return listResponse("Documents", items, collRid(req.accountName(), dbId, collId));
    }

    private Response createDocument(AzureRequest req, String dbId, String collId) {
        Optional<StoredObject> collFound = store.get(collKey(req.accountName(), dbId, collId));
        if (collFound.isEmpty()) return notFound(collId);

        Map<String, Object> body = parseBody(req);
        String id = body.containsKey("id") ? String.valueOf(body.get("id")) : UUID.randomUUID().toString();
        body.put("id", id);

        boolean upsert = "True".equalsIgnoreCase(req.headers().getHeaderString("x-ms-documentdb-is-upsert"));

        Map<String, Object> collMeta = parseData(collFound.get());
        String pk     = resolvePartitionKey(body, collMeta, req);
        String pkEnc  = encodeKey(pk);
        String docKey = docKey(req.accountName(), dbId, collId, pkEnc, id);

        if (!upsert && store.get(docKey).isPresent())
            return errorResponse(409, "Conflict", "Document with id '" + id + "' already exists.");

        Instant now  = Instant.now();
        String  rid  = newRid(12);
        String  etag = newEtag();

        body.put("_rid",         rid);
        body.put("_self",        "dbs/" + dbId + "/colls/" + collId + "/docs/" + id);
        body.put("_etag",        quoted(etag));
        body.put("_ts",          now.getEpochSecond());
        body.put("_attachments", "attachments/");

        store.put(docKey, stored(docKey, body, now, etag));
        return cosmosResponse(body, Response.Status.CREATED, etag);
    }

    private Response getDocument(AzureRequest req, String dbId, String collId, String docId) {
        StoredObject obj = findDoc(req, dbId, collId, docId);
        if (obj == null) return notFound(docId);
        return cosmosResponse(parseData(obj), Response.Status.OK, obj.etag());
    }

    private Response replaceDocument(AzureRequest req, String dbId, String collId, String docId) {
        Optional<StoredObject> collFound = store.get(collKey(req.accountName(), dbId, collId));
        if (collFound.isEmpty()) return notFound(collId);

        Map<String, Object> body = parseBody(req);
        body.put("id", docId);

        Map<String, Object> collMeta = parseData(collFound.get());
        String pk     = resolvePartitionKey(body, collMeta, req);
        String pkEnc  = encodeKey(pk);
        String docKey = docKey(req.accountName(), dbId, collId, pkEnc, docId);

        if (store.get(docKey).isEmpty()) return notFound(docId);

        Instant now  = Instant.now();
        String  etag = newEtag();
        Map<String, Object> old = parseData(store.get(docKey).get());

        body.put("_rid",         old.getOrDefault("_rid", newRid(12)));
        body.put("_self",        "dbs/" + dbId + "/colls/" + collId + "/docs/" + docId);
        body.put("_etag",        quoted(etag));
        body.put("_ts",          now.getEpochSecond());
        body.put("_attachments", "attachments/");

        store.put(docKey, stored(docKey, body, now, etag));
        return cosmosResponse(body, Response.Status.OK, etag);
    }

    private Response deleteDocument(AzureRequest req, String dbId, String collId, String docId) {
        StoredObject obj = findDoc(req, dbId, collId, docId);
        if (obj == null) return notFound(docId);
        store.delete(obj.key());
        return Response.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    private Response queryDocuments(AzureRequest req, String dbId, String collId) {
        if (store.get(collKey(req.accountName(), dbId, collId)).isEmpty()) return notFound(collId);

        Map<String, Object> body = parseBody(req);
        String sql = (String) body.getOrDefault("query", "SELECT * FROM c");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> params = body.get("parameters") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();

        String prefix = req.accountName() + K_DOC + dbId + "|" + collId + "|";
        List<Map<String, Object>> allDocs = store.scan(k -> k.startsWith(prefix))
                .stream().map(this::parseData).collect(Collectors.toList());

        CosmosQueryEngine.QueryResult result = queryEngine.execute(sql, params, allDocs);
        return queryResponse(result, collRid(req.accountName(), dbId, collId));
    }

    // -----------------------------------------------------------------------
    // Document lookup (with partition key awareness)
    // -----------------------------------------------------------------------

    private StoredObject findDoc(AzureRequest req, String dbId, String collId, String docId) {
        // Fast path: construct exact key using partition key from header
        Optional<StoredObject> collFound = store.get(collKey(req.accountName(), dbId, collId));
        if (collFound.isPresent()) {
            String pk    = extractPartitionKeyValue(req);
            String pkEnc = encodeKey(pk);
            String exact = docKey(req.accountName(), dbId, collId, pkEnc, docId);
            Optional<StoredObject> found = store.get(exact);
            if (found.isPresent()) return found.get();
        }
        // Fallback: scan (handles missing PK header or cross-partition reads)
        String prefix = req.accountName() + K_DOC + dbId + "|" + collId + "|";
        return store.scan(k -> k.startsWith(prefix) && k.endsWith("|" + docId))
                .stream().findFirst().orElse(null);
    }

    // -----------------------------------------------------------------------
    // Partition key helpers
    // -----------------------------------------------------------------------

    private String extractPartitionKeyValue(AzureRequest req) {
        String header = req.headers().getHeaderString("x-ms-documentdb-partitionkey");
        if (header == null || header.isBlank() || "[]".equals(header.trim())) return "";
        try {
            Object[] arr = MAPPER.readValue(header, Object[].class);
            return (arr.length > 0 && arr[0] != null) ? String.valueOf(arr[0]) : "";
        } catch (Exception e) {
            return header;
        }
    }

    @SuppressWarnings("unchecked")
    private String resolvePartitionKey(Map<String, Object> doc, Map<String, Object> collMeta, AzureRequest req) {
        String fromHeader = extractPartitionKeyValue(req);
        if (!fromHeader.isEmpty()) return fromHeader;

        if (collMeta.get("partitionKey") instanceof Map<?, ?> pkConf) {
            Object paths = ((Map<String, Object>) pkConf).get("paths");
            if (paths instanceof List<?> list && !list.isEmpty()) {
                String path = String.valueOf(list.get(0));
                if (path.startsWith("/")) path = path.substring(1);
                Object val = doc.get(path);
                return val != null ? String.valueOf(val) : "";
            }
        }
        return "";
    }

    // -----------------------------------------------------------------------
    // Response builders
    // -----------------------------------------------------------------------

    private Response cosmosResponse(Map<String, Object> body, Response.Status status, String etag) {
        try {
            return Response.status(status)
                    .entity(MAPPER.writeValueAsString(body))
                    .type("application/json")
                    .header("etag",                  quoted(etag))
                    .header("x-ms-request-charge",   "1")
                    .header("x-ms-session-token",    "0:1")
                    .header("x-ms-activity-id",      UUID.randomUUID().toString())
                    .header("x-ms-version",          "2018-12-31")
                    .build();
        } catch (JsonProcessingException e) {
            return Response.serverError().build();
        }
    }

    private Response listResponse(String arrayKey, List<Map<String, Object>> items, String rid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("_rid",   rid);
        body.put("_count", items.size());
        body.put(arrayKey, items);
        try {
            return Response.ok(MAPPER.writeValueAsString(body), "application/json")
                    .header("x-ms-request-charge", "1")
                    .header("x-ms-item-count",     String.valueOf(items.size()))
                    .header("x-ms-activity-id",    UUID.randomUUID().toString())
                    .header("x-ms-version",        "2018-12-31")
                    .build();
        } catch (JsonProcessingException e) {
            return Response.serverError().build();
        }
    }

    private Response queryResponse(CosmosQueryEngine.QueryResult result, String rid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("_rid",      rid);
        body.put("_count",    result.count());
        body.put("Documents", result.items());
        try {
            return Response.ok(MAPPER.writeValueAsString(body), "application/json")
                    .header("x-ms-request-charge", "1")
                    .header("x-ms-item-count",     String.valueOf(result.count()))
                    .header("x-ms-activity-id",    UUID.randomUUID().toString())
                    .header("x-ms-version",        "2018-12-31")
                    .build();
        } catch (JsonProcessingException e) {
            return Response.serverError().build();
        }
    }

    private Response notFound(String id) {
        return errorResponse(404, "NotFound",
                "Resource Not Found. Learn more: https://aka.ms/cosmosdb-tshoot404. ActivityId: " + UUID.randomUUID());
    }

    private Response errorResponse(int status, String code, String message) {
        Map<String, Object> body = Map.of("code", code, "message", message);
        try {
            return Response.status(status)
                    .entity(MAPPER.writeValueAsString(body))
                    .type("application/json")
                    .header("x-ms-activity-id", UUID.randomUUID().toString())
                    .build();
        } catch (JsonProcessingException e) {
            return Response.status(status).build();
        }
    }

    private Response notImplemented() { return Response.status(501).build(); }

    // -----------------------------------------------------------------------
    // Storage key helpers
    // -----------------------------------------------------------------------

    private String dbKey(String account, String dbId) {
        return account + K_DB + dbId;
    }

    private String collKey(String account, String dbId, String collId) {
        return account + K_COLL + dbId + "|" + collId;
    }

    private String docKey(String account, String dbId, String collId, String pkEncoded, String docId) {
        return account + K_DOC + dbId + "|" + collId + "|" + pkEncoded + "|" + docId;
    }

    private String encodeKey(String value) {
        if (value == null || value.isEmpty()) return "_";
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------
    // _rid helpers (read from stored metadata)
    // -----------------------------------------------------------------------

    private String dbRid(String account, String dbId) {
        return store.get(dbKey(account, dbId))
                .map(o -> (String) parseData(o).getOrDefault("_rid", ""))
                .orElse("");
    }

    private String collRid(String account, String dbId, String collId) {
        return store.get(collKey(account, dbId, collId))
                .map(o -> (String) parseData(o).getOrDefault("_rid", ""))
                .orElse("");
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private StoredObject stored(String key, Map<String, Object> data, Instant now, String etag) {
        return new StoredObject(key, toBytes(data), Map.of(), now, etag);
    }

    private Map<String, Object> parseData(StoredObject obj) {
        try {
            return MAPPER.readValue(obj.data(), new TypeReference<>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> parseBody(AzureRequest req) {
        try {
            return MAPPER.readValue(req.bodyStream(), new TypeReference<>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private byte[] toBytes(Object obj) {
        try { return MAPPER.writeValueAsBytes(obj); }
        catch (JsonProcessingException e) { return new byte[0]; }
    }

    private Map<String, Object> defaultIndexingPolicy() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("automatic",    true);
        p.put("indexingMode", "consistent");
        p.put("includedPaths", List.of(Map.of("path", "/*")));
        p.put("excludedPaths", List.of(Map.of("path", "/\"_etag\"/?")));
        return p;
    }

    private String newRid(int byteLen) {
        byte[] b = new byte[byteLen];
        new Random().nextBytes(b);
        return Base64.getEncoder().withoutPadding().encodeToString(b);
    }

    private String newEtag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String quoted(String s) { return "\"" + s + "\""; }

    public void clearAll() { store.clear(); }
}
