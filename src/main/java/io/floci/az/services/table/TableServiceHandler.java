package io.floci.az.services.table;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureErrorResponse;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@ApplicationScoped
public class TableServiceHandler implements AzureServiceHandler {

    private static final Logger LOGGER = Logger.getLogger(TableServiceHandler.class);

    private static final String NS_PREFIX = "__ns__:";
    private static final StoredObject NS_SENTINEL =
            new StoredObject("", new byte[0], Map.of(), Instant.EPOCH, "");
    private static final DateTimeFormatter ISO_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .withZone(ZoneId.of("UTC"));
    private static final int DEFAULT_PAGE_SIZE = 1000;

    private final StorageBackend<String, StoredObject> store;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    public TableServiceHandler(StorageFactory storageFactory) {
        this.store = storageFactory.create("table");
    }

    @Override
    public String getServiceType() {
        return "table";
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return "table".equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest request) {
        String path = request.resourcePath();
        String method = request.method();

        LOGGER.infof("TableService handling: %s %s", method, path);

        // Feature 6: $batch routing
        if ("$batch".equals(path) && "POST".equalsIgnoreCase(method)) {
            Response batchResponse = executeBatch(request);
            return Response.fromResponse(batchResponse)
                    .header("x-ms-request-id", UUID.randomUUID().toString())
                    .header("x-ms-version", request.headers().getHeaderString("x-ms-version"))
                    .header("DataServiceVersion", "3.0;")
                    .build();
        }

        Response response;
        if (path.startsWith("Tables")) {
            if ("GET".equalsIgnoreCase(method)) {
                response = listTables(request);
            } else if ("POST".equalsIgnoreCase(method)) {
                response = createTable(request);
            } else if ("DELETE".equalsIgnoreCase(method)) {
                response = deleteTable(request, extractTableNameFromTablesPath(path));
            } else {
                response = new AzureErrorResponse("NotImplemented", "The requested operation is not implemented.")
                        .toJsonResponse(501);
            }
        } else {
            String tableName;
            String pkRkPart = "";
            if (path.contains("(")) {
                tableName = path.substring(0, path.indexOf("("));
                pkRkPart = path.substring(path.indexOf("(") + 1, path.lastIndexOf(")"));
            } else {
                tableName = path;
            }

            if ("POST".equalsIgnoreCase(method)) {
                response = pkRkPart.isEmpty()
                        ? insertEntity(request, tableName)
                        : updateEntity(request, tableName, pkRkPart);
            } else if ("GET".equalsIgnoreCase(method)) {
                response = pkRkPart.isEmpty()
                        ? queryEntities(request, tableName)
                        : getEntity(request, tableName, pkRkPart);
            } else if ("DELETE".equalsIgnoreCase(method)) {
                response = deleteEntity(request, tableName, pkRkPart);
            } else if ("PUT".equalsIgnoreCase(method) || "MERGE".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
                response = updateEntity(request, tableName, pkRkPart);
            } else {
                response = new AzureErrorResponse("NotImplemented", "The requested operation is not implemented.")
                        .toJsonResponse(501);
            }
        }

        return Response.fromResponse(response)
                .header("x-ms-request-id", UUID.randomUUID().toString())
                .header("x-ms-version", request.headers().getHeaderString("x-ms-version"))
                .header("DataServiceVersion", "3.0;")
                .build();
    }

    // -------------------------------------------------------------------------
    // Table lifecycle
    // -------------------------------------------------------------------------

    private Response createTable(AzureRequest request) {
        try {
            TableModel.TableCreateRequest req = objectMapper.readValue(request.bodyStream(), TableModel.TableCreateRequest.class);
            String key = nsKey(request.accountName(), req.TableName());
            if (store.get(key).isPresent()) {
                return new AzureErrorResponse("TableAlreadyExists", "The table specified already exists.")
                        .toJsonResponse(Response.Status.CONFLICT.getStatusCode());
            }
            store.put(key, NS_SENTINEL);
            return Response.status(Response.Status.CREATED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new TableModel.TableItem(req.TableName()))
                    .build();
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }

    private Response listTables(AzureRequest request) {
        String nsFilter = NS_PREFIX + request.accountName() + "/";
        List<TableModel.TableItem> tables = store.keys().stream()
                .filter(k -> k.startsWith(nsFilter))
                .map(k -> k.substring(nsFilter.length()))
                .map(TableModel.TableItem::new)
                .collect(Collectors.toList());

        return Response.ok(new TableModel.TableListResponse(tables))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private Response deleteTable(AzureRequest request, String tableName) {
        store.delete(nsKey(request.accountName(), tableName));
        String keyPrefix = objKey(request.accountName(), tableName, "");
        store.keys().stream()
                .filter(k -> k.startsWith(keyPrefix))
                .toList()
                .forEach(store::delete);
        return Response.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Entity CRUD
    // -------------------------------------------------------------------------

    private Response insertEntity(AzureRequest request, String tableName) {
        try {
            Map<String, Object> entity = new LinkedHashMap<>(objectMapper.readValue(request.bodyStream(), Map.class));
            String pk = (String) entity.get("PartitionKey");
            String rk = (String) entity.get("RowKey");
            if (pk == null || rk == null) {
                return new AzureErrorResponse("PropertiesNeedValue",
                        "PartitionKey and RowKey are required.")
                        .toJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }
            String key = pk + "_" + rk;
            String etag = UUID.randomUUID().toString();
            entity.put("Timestamp", ISO_TIMESTAMP.format(Instant.now()));

            store.put(objKey(request.accountName(), tableName, key),
                    new StoredObject(key, objectMapper.writeValueAsBytes(entity), Map.of(), Instant.now(), etag));

            // Feature 5: Prefer header handling
            String prefer = request.headers().getHeaderString("Prefer");
            if ("return-no-content".equalsIgnoreCase(prefer != null ? prefer.trim() : "")) {
                return Response.noContent()
                        .header("ETag", etag)
                        .header("Preference-Applied", "return-no-content")
                        .build();
            } else {
                return Response.status(Response.Status.CREATED)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(entity)
                        .header("ETag", etag)
                        .header("Preference-Applied", "return-content")
                        .build();
            }
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }

    private Response getEntity(AzureRequest request, String tableName, String pkRkPart) {
        String pk = extractValue(pkRkPart, "PartitionKey");
        String rk = extractValue(pkRkPart, "RowKey");
        Optional<StoredObject> object = store.get(objKey(request.accountName(), tableName, pk + "_" + rk));

        if (object.isEmpty()) {
            return new AzureErrorResponse("ResourceNotFound", "The specified resource does not exist.")
                    .toJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }

        try {
            Map<String, Object> entity = new LinkedHashMap<>(
                    objectMapper.readValue(object.get().data(), Map.class));
            String etag = object.get().etag();
            entity.put("odata.etag", etag);   // SDKs read etag from the response body
            return Response.ok(entity)
                    .type(MediaType.APPLICATION_JSON)
                    .header("ETag", etag)
                    .build();
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }

    /**
     * Features 1, 2, 3: queryEntities with OData $filter, $select projection,
     * and $top + server-side pagination with continuation tokens.
     */
    private Response queryEntities(AzureRequest request, String tableName) {
        String keyPrefix = objKey(request.accountName(), tableName, "");

        // Feature 1: compile $filter predicate
        String filterParam = request.queryParams().get("$filter");
        Predicate<Map<String, Object>> filterPred = ODataFilter.compile(filterParam);

        // Feature 2: parse $select fields
        String selectParam = request.queryParams().get("$select");
        Set<String> selectFields = null;
        if (selectParam != null && !selectParam.isBlank()) {
            selectFields = Arrays.stream(selectParam.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        // Feature 3: parse $top and continuation token
        int pageSize = DEFAULT_PAGE_SIZE;
        String topParam = request.queryParams().get("$top");
        if (topParam != null) {
            try {
                int top = Integer.parseInt(topParam.trim());
                pageSize = Math.min(top, DEFAULT_PAGE_SIZE);
            } catch (NumberFormatException ignored) {
                // ignore, use default
            }
        }

        String nextPk = request.queryParams().get("NextPartitionKey");
        String nextRk = request.queryParams().get("NextRowKey");

        // Scan → deserialize keeping EntityWithMeta → filter → sort
        List<TableModel.EntityWithMeta> sorted;
        try {
            sorted = store.scan(k -> k.startsWith(keyPrefix)).stream()
                    .map(so -> {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> entity = objectMapper.readValue(so.data(), Map.class);
                            return new TableModel.EntityWithMeta(entity, so);
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .filter(em -> filterPred.test(em.entity()))
                    .sorted(Comparator
                            .comparing((TableModel.EntityWithMeta em) ->
                                    (String) em.entity().getOrDefault("PartitionKey", ""))
                            .thenComparing(em ->
                                    (String) em.entity().getOrDefault("RowKey", "")))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Response.serverError().build();
        }

        // Feature 3: find start index from continuation token
        int startIdx = 0;
        if (nextPk != null && nextRk != null) {
            final String fNextPk = nextPk;
            final String fNextRk = nextRk;
            for (int i = 0; i < sorted.size(); i++) {
                Map<String, Object> entity = sorted.get(i).entity();
                String epk = (String) entity.getOrDefault("PartitionKey", "");
                String erk = (String) entity.getOrDefault("RowKey", "");
                int pkCmp = epk.compareTo(fNextPk);
                if (pkCmp > 0 || (pkCmp == 0 && erk.compareTo(fNextRk) >= 0)) {
                    startIdx = i;
                    break;
                }
                // If we never find the position, startIdx stays 0 (or we can set it to size)
                if (i == sorted.size() - 1) {
                    startIdx = sorted.size(); // no matches
                }
            }
        }

        // Slice the page
        int endIdx = Math.min(startIdx + pageSize, sorted.size());
        List<TableModel.EntityWithMeta> page = sorted.subList(startIdx, endIdx);

        // Determine if there are more results and build continuation token
        boolean hasMore = endIdx < sorted.size();
        String continuationPk = null;
        String continuationRk = null;
        if (hasMore) {
            TableModel.EntityWithMeta nextEntity = sorted.get(endIdx);
            continuationPk = (String) nextEntity.entity().getOrDefault("PartitionKey", "");
            continuationRk = (String) nextEntity.entity().getOrDefault("RowKey", "");
        }

        // Feature 2: $select projection
        final Set<String> finalSelectFields = selectFields;
        List<Map<String, Object>> projected = page.stream()
                .map(em -> projectEntity(em.entity(), em.stored(), finalSelectFields))
                .collect(Collectors.toList());

        Response.ResponseBuilder rb = Response.ok(new TableModel.EntityListResponse(projected))
                .type(MediaType.APPLICATION_JSON);

        if (hasMore) {
            rb.header("x-ms-continuation-NextPartitionKey", continuationPk);
            rb.header("x-ms-continuation-NextRowKey", continuationRk);
        }

        return rb.build();
    }

    /**
     * Project an entity map according to $select rules (Feature 2).
     * Always includes PartitionKey, RowKey, Timestamp, and odata.etag.
     */
    private Map<String, Object> projectEntity(Map<String, Object> entity, StoredObject stored, Set<String> selectFields) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (selectFields == null) {
            // Include all entity fields
            result.putAll(entity);
            // Inject odata.etag
            result.put("odata.etag", stored.etag());
        } else {
            // Always include system fields
            result.put("PartitionKey", entity.get("PartitionKey"));
            result.put("RowKey", entity.get("RowKey"));
            result.put("Timestamp", entity.get("Timestamp"));
            result.put("odata.etag", stored.etag());

            // Include selected fields
            for (String field : selectFields) {
                if (entity.containsKey(field)) {
                    result.put(field, entity.get(field));
                }
                // Include odata.type annotation if present
                String typeAnnotation = field + "@odata.type";
                if (entity.containsKey(typeAnnotation)) {
                    result.put(typeAnnotation, entity.get(typeAnnotation));
                }
            }
        }

        return result;
    }

    /**
     * Feature 4: deleteEntity with ETag checking and 404 when not found.
     */
    private Response deleteEntity(AzureRequest request, String tableName, String pkRkPart) {
        String pk = extractValue(pkRkPart, "PartitionKey");
        String rk = extractValue(pkRkPart, "RowKey");
        String storeKey = objKey(request.accountName(), tableName, pk + "_" + rk);

        Optional<StoredObject> existing = store.get(storeKey);
        if (existing.isEmpty()) {
            return new AzureErrorResponse("ResourceNotFound", "The specified resource does not exist.")
                    .toJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }

        String ifMatch = request.headers().getHeaderString("If-Match");
        Response etagCheck = checkEtag(ifMatch, existing.get());
        if (etagCheck != null) return etagCheck;

        store.delete(storeKey);
        return Response.noContent().build();
    }

    /**
     * Feature 4: updateEntity with ETag checking.
     * PUT = full replace; MERGE/PATCH = partial merge.
     */
    private Response updateEntity(AzureRequest request, String tableName, String pkRkPart) {
        try {
            String method = request.method();
            Map<String, Object> incoming = new LinkedHashMap<>(objectMapper.readValue(request.bodyStream(), Map.class));
            String pk = (String) incoming.get("PartitionKey");
            String rk = (String) incoming.get("RowKey");
            if (pk == null || rk == null) {
                pk = extractValue(pkRkPart, "PartitionKey");
                rk = extractValue(pkRkPart, "RowKey");
                incoming.put("PartitionKey", pk);
                incoming.put("RowKey", rk);
            }
            if (pk.isEmpty() || rk.isEmpty()) {
                return new AzureErrorResponse("PropertiesNeedValue",
                        "PartitionKey and RowKey are required.")
                        .toJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }
            String key = pk + "_" + rk;
            String storeKey = objKey(request.accountName(), tableName, key);

            Optional<StoredObject> existing = store.get(storeKey);

            // Feature 4: ETag check
            String ifMatch = request.headers().getHeaderString("If-Match");
            Response etagCheck = checkEtag(ifMatch, existing.orElse(null));
            if (etagCheck != null) return etagCheck;

            Map<String, Object> entity;
            if ("MERGE".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
                // Partial merge: read existing and overlay new fields
                if (existing.isPresent()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingEntity = new LinkedHashMap<>(
                            objectMapper.readValue(existing.get().data(), Map.class));
                    existingEntity.putAll(incoming);
                    entity = existingEntity;
                } else {
                    entity = incoming;
                }
            } else {
                // PUT: full replace
                entity = incoming;
            }

            String etag = UUID.randomUUID().toString();
            entity.put("Timestamp", ISO_TIMESTAMP.format(Instant.now()));

            store.put(storeKey,
                    new StoredObject(key, objectMapper.writeValueAsBytes(entity), Map.of(), Instant.now(), etag));

            return Response.noContent()
                    .header("ETag", etag)
                    .build();
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }

    /**
     * Feature 4: Check If-Match header against a stored object's etag.
     * Returns a non-null Response if the check fails, null if it passes.
     */
    private Response checkEtag(String ifMatch, StoredObject stored) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null; // no check required
        }
        if ("*".equals(ifMatch.trim())) {
            // Entity must exist
            if (stored == null) {
                return new AzureErrorResponse("ResourceNotFound", "The specified resource does not exist.")
                        .toJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
            }
            return null; // exists, pass
        }
        // Specific etag comparison
        String requestedEtag = ifMatch.trim();
        if (requestedEtag.startsWith("\"") && requestedEtag.endsWith("\"")) {
            requestedEtag = requestedEtag.substring(1, requestedEtag.length() - 1);
        }
        if (stored == null || !requestedEtag.equals(stored.etag())) {
            // 412 Precondition Failed — raw OData error shape
            Map<String, Object> errorBody = Map.of(
                    "odata.error", Map.of(
                            "code", "UpdateConditionNotSatisfied",
                            "message", Map.of(
                                    "lang", "en-US",
                                    "value", "The update condition specified in the request was not satisfied."
                            )
                    )
            );
            try {
                return Response.status(412)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(objectMapper.writeValueAsString(errorBody))
                        .build();
            } catch (IOException e) {
                return Response.status(412).build();
            }
        }
        return null; // etag matches, pass
    }

    // -------------------------------------------------------------------------
    // Feature 6: $batch — Entity Group Transactions
    // -------------------------------------------------------------------------

    private Response executeBatch(AzureRequest request) {
        try {
            byte[] bodyBytes = request.bodyStream().readAllBytes();
            String body = new String(bodyBytes, StandardCharsets.UTF_8);

            // Extract outer boundary from Content-Type header
            String contentType = request.headers().getHeaderString("Content-Type");
            String outerBoundary = extractBoundary(contentType);
            if (outerBoundary == null) {
                return new AzureErrorResponse("InvalidInput", "Missing or invalid Content-Type boundary.")
                        .toJsonResponse(400);
            }

            // Split on outer boundary
            String[] outerParts = splitOnBoundary(body, outerBoundary);
            // outerParts[0] = preamble, outerParts[last] = terminator (or empty after --)
            // We process parts 1..n-1 (skip preamble and terminator)

            // Collect all batch operations
            record BatchOp(
                String accountName, String tableName, String method,
                String pkRkPart, Map<String, Object> entityBody, String ifMatch
            ) {}

            List<BatchOp> ops = new ArrayList<>();

            for (int i = 1; i < outerParts.length - 1; i++) {
                String outerPart = outerParts[i];

                // Parse inner Content-Type to get changeset boundary
                String innerContentType = extractHeaderFromPart(outerPart, "Content-Type");
                if (innerContentType == null) continue;
                String changesetBoundary = extractBoundary(innerContentType);
                if (changesetBoundary == null) continue;

                // Extract body of outer part (after blank line)
                String outerBody = extractBodyAfterHeaders(outerPart);
                if (outerBody == null) continue;

                // Split on changeset boundary
                String[] innerParts = splitOnBoundary(outerBody, changesetBoundary);

                for (int j = 1; j < innerParts.length - 1; j++) {
                    String innerPart = innerParts[j];

                    // Find double-blank-line separator to get embedded HTTP request
                    String embeddedHttp = extractBodyAfterHeaders(innerPart);
                    if (embeddedHttp == null) continue;

                    // Parse embedded HTTP request
                    // First line: METHOD /path HTTP/1.1
                    String[] lines = embeddedHttp.split("\r\n|\n", -1);
                    if (lines.length == 0) continue;

                    String requestLine = lines[0].trim();
                    String[] requestParts = requestLine.split("\\s+", 3);
                    if (requestParts.length < 2) continue;

                    String opMethod = requestParts[0];
                    String opPath = requestParts[1];

                    // Parse headers until blank line, then body
                    Map<String, String> embeddedHeaders = new LinkedHashMap<>();
                    int bodyStart = 1;
                    for (int k = 1; k < lines.length; k++) {
                        String line = lines[k];
                        if (line.isBlank()) {
                            bodyStart = k + 1;
                            break;
                        }
                        int colonIdx = line.indexOf(':');
                        if (colonIdx > 0) {
                            embeddedHeaders.put(
                                    line.substring(0, colonIdx).trim().toLowerCase(),
                                    line.substring(colonIdx + 1).trim()
                            );
                        }
                    }

                    // Collect body lines
                    StringBuilder embeddedBodySb = new StringBuilder();
                    for (int k = bodyStart; k < lines.length; k++) {
                        if (k > bodyStart) embeddedBodySb.append("\n");
                        embeddedBodySb.append(lines[k]);
                    }
                    String embeddedBody = embeddedBodySb.toString().trim();

                    // Parse path: strip scheme+host (for absolute URLs) + account prefix
                    // Handles both relative (/devstoreaccount1-table/Table) and
                    // absolute (http://host:port/devstoreaccount1-table/Table) URLs
                    String opAccountName = request.accountName();
                    String tablePortion = opPath;
                    if (tablePortion.contains("://")) {
                        // Absolute URL: extract path portion after host:port
                        int schemeEnd = tablePortion.indexOf("://");
                        int pathStart = tablePortion.indexOf('/', schemeEnd + 3);
                        tablePortion = (pathStart >= 0) ? tablePortion.substring(pathStart) : "/";
                    }
                    if (tablePortion.startsWith("/")) {
                        tablePortion = tablePortion.substring(1);
                    }
                    // Strip account-service prefix (e.g. "devstoreaccount1-table/")
                    int slashIdx = tablePortion.indexOf('/');
                    if (slashIdx > 0) {
                        tablePortion = tablePortion.substring(slashIdx + 1);
                    }

                    // Parse tableName and pkRkPart (same logic as main routing)
                    String opTableName;
                    String opPkRkPart = "";
                    if (tablePortion.contains("(")) {
                        opTableName = tablePortion.substring(0, tablePortion.indexOf("("));
                        opPkRkPart = tablePortion.substring(
                                tablePortion.indexOf("(") + 1,
                                tablePortion.lastIndexOf(")"));
                    } else {
                        opTableName = tablePortion;
                    }

                    // Parse entity body if present
                    Map<String, Object> entityBody = null;
                    if (!embeddedBody.isEmpty()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> parsed = objectMapper.readValue(embeddedBody, Map.class);
                            entityBody = parsed;
                        } catch (Exception ignored) {
                            entityBody = new LinkedHashMap<>();
                        }
                    }

                    String ifMatch = embeddedHeaders.get("if-match");

                    ops.add(new BatchOp(opAccountName, opTableName, opMethod, opPkRkPart, entityBody, ifMatch));
                }
            }

            // Execute atomically: save originals before starting
            Map<String, Optional<StoredObject>> originals = new LinkedHashMap<>();
            for (BatchOp op : ops) {
                if (!op.pkRkPart().isEmpty()) {
                    String pk = extractValue(op.pkRkPart(), "PartitionKey");
                    String rk = extractValue(op.pkRkPart(), "RowKey");
                    if (!pk.isEmpty() && !rk.isEmpty()) {
                        String storeKey = objKey(op.accountName(), op.tableName(), pk + "_" + rk);
                        originals.putIfAbsent(storeKey, store.get(storeKey));
                    }
                } else if (op.entityBody() != null) {
                    String pk = (String) op.entityBody().get("PartitionKey");
                    String rk = (String) op.entityBody().get("RowKey");
                    if (pk != null && rk != null) {
                        String storeKey = objKey(op.accountName(), op.tableName(), pk + "_" + rk);
                        originals.putIfAbsent(storeKey, store.get(storeKey));
                    }
                }
            }

            // Execute all operations, collecting results
            List<Response> results = new ArrayList<>();
            boolean failed = false;
            int failedIdx = -1;
            Response failedResponse = null;

            for (int i = 0; i < ops.size(); i++) {
                BatchOp op = ops.get(i);
                Response opResponse = executeBatchOp(op.accountName(), op.tableName(),
                        op.method(), op.pkRkPart(), op.entityBody(), op.ifMatch());
                results.add(opResponse);
                if (opResponse.getStatus() >= 400) {
                    failed = true;
                    failedIdx = i;
                    failedResponse = opResponse;
                    break;
                }
            }

            if (failed) {
                // Restore all originals
                for (Map.Entry<String, Optional<StoredObject>> entry : originals.entrySet()) {
                    if (entry.getValue().isPresent()) {
                        store.put(entry.getKey(), entry.getValue().get());
                    } else {
                        store.delete(entry.getKey());
                    }
                }
                // Return error batch response
                return buildErrorBatchResponse(failedIdx, failedResponse);
            }

            // Build successful multipart response
            return buildBatchResponse(ops.stream().map(BatchOp::method).toList(), results);

        } catch (Exception e) {
            LOGGER.errorf(e, "Error executing batch");
            return Response.serverError().build();
        }
    }

    /**
     * Execute a single batch operation using the direct overloads.
     */
    private Response executeBatchOp(String accountName, String tableName, String method,
                                     String pkRkPart, Map<String, Object> entityBody, String ifMatch) {
        if ("POST".equalsIgnoreCase(method) || ("PUT".equalsIgnoreCase(method) && pkRkPart.isEmpty())) {
            // INSERT
            return insertEntityDirect(accountName, tableName, entityBody);
        } else if ("PUT".equalsIgnoreCase(method) || "MERGE".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            return updateEntityDirect(accountName, tableName, method, pkRkPart, entityBody, ifMatch);
        } else if ("DELETE".equalsIgnoreCase(method)) {
            return deleteEntityDirect(accountName, tableName, pkRkPart, ifMatch);
        } else {
            return new AzureErrorResponse("NotImplemented", "The requested operation is not implemented.")
                    .toJsonResponse(501);
        }
    }

    /**
     * Direct INSERT for batch — always returns 201 + body (Prefer: return-content behavior).
     */
    private Response insertEntityDirect(String accountName, String tableName, Map<String, Object> entityBody) {
        try {
            if (entityBody == null) {
                return new AzureErrorResponse("PropertiesNeedValue", "Entity body is required.")
                        .toJsonResponse(400);
            }
            Map<String, Object> entity = new LinkedHashMap<>(entityBody);
            String pk = (String) entity.get("PartitionKey");
            String rk = (String) entity.get("RowKey");
            if (pk == null || rk == null) {
                return new AzureErrorResponse("PropertiesNeedValue", "PartitionKey and RowKey are required.")
                        .toJsonResponse(400);
            }
            String key = pk + "_" + rk;
            String etag = UUID.randomUUID().toString();
            entity.put("Timestamp", ISO_TIMESTAMP.format(Instant.now()));

            store.put(objKey(accountName, tableName, key),
                    new StoredObject(key, objectMapper.writeValueAsBytes(entity), Map.of(), Instant.now(), etag));

            return Response.status(201)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(entity)
                    .header("ETag", etag)
                    .build();
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }

    /**
     * Direct UPDATE/MERGE for batch — returns 204 on success.
     */
    private Response updateEntityDirect(String accountName, String tableName, String method,
                                         String pkRkPart, Map<String, Object> entityBody, String ifMatch) {
        try {
            if (entityBody == null) entityBody = new LinkedHashMap<>();
            Map<String, Object> incoming = new LinkedHashMap<>(entityBody);
            String pk = (String) incoming.get("PartitionKey");
            String rk = (String) incoming.get("RowKey");
            if (pk == null || rk == null) {
                pk = extractValue(pkRkPart, "PartitionKey");
                rk = extractValue(pkRkPart, "RowKey");
                incoming.put("PartitionKey", pk);
                incoming.put("RowKey", rk);
            }
            if (pk == null || pk.isEmpty() || rk == null || rk.isEmpty()) {
                return new AzureErrorResponse("PropertiesNeedValue", "PartitionKey and RowKey are required.")
                        .toJsonResponse(400);
            }
            String key = pk + "_" + rk;
            String storeKey = objKey(accountName, tableName, key);
            Optional<StoredObject> existing = store.get(storeKey);

            Response etagCheck = checkEtag(ifMatch, existing.orElse(null));
            if (etagCheck != null) return etagCheck;

            Map<String, Object> entity;
            if ("MERGE".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
                if (existing.isPresent()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingEntity = new LinkedHashMap<>(
                            objectMapper.readValue(existing.get().data(), Map.class));
                    existingEntity.putAll(incoming);
                    entity = existingEntity;
                } else {
                    entity = incoming;
                }
            } else {
                entity = incoming;
            }

            String etag = UUID.randomUUID().toString();
            entity.put("Timestamp", ISO_TIMESTAMP.format(Instant.now()));

            store.put(storeKey,
                    new StoredObject(key, objectMapper.writeValueAsBytes(entity), Map.of(), Instant.now(), etag));

            return Response.noContent().header("ETag", etag).build();
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }

    /**
     * Direct DELETE for batch — returns 204 on success.
     */
    private Response deleteEntityDirect(String accountName, String tableName, String pkRkPart, String ifMatch) {
        String pk = extractValue(pkRkPart, "PartitionKey");
        String rk = extractValue(pkRkPart, "RowKey");
        String storeKey = objKey(accountName, tableName, pk + "_" + rk);

        Optional<StoredObject> existing = store.get(storeKey);
        if (existing.isEmpty()) {
            return new AzureErrorResponse("ResourceNotFound", "The specified resource does not exist.")
                    .toJsonResponse(404);
        }

        Response etagCheck = checkEtag(ifMatch, existing.get());
        if (etagCheck != null) return etagCheck;

        store.delete(storeKey);
        return Response.noContent().build();
    }

    /**
     * Build the multipart batch response for a set of successful operations.
     */
    private Response buildBatchResponse(List<String> methods, List<Response> results) {
        String batchId = UUID.randomUUID().toString().replace("-", "");
        String changesetId = UUID.randomUUID().toString().replace("-", "");
        String batchBoundary = "batchresponse_" + batchId;
        String changesetBoundary = "changesetresponse_" + changesetId;

        StringBuilder sb = new StringBuilder();

        sb.append("--").append(batchBoundary).append("\r\n");
        sb.append("Content-Type: multipart/mixed; boundary=").append(changesetBoundary).append("\r\n");
        sb.append("\r\n");

        for (int i = 0; i < results.size(); i++) {
            Response r = results.get(i);
            sb.append("--").append(changesetBoundary).append("\r\n");
            sb.append("Content-Type: application/http\r\n");
            sb.append("Content-Transfer-Encoding: binary\r\n");
            sb.append("\r\n");

            int status = r.getStatus();
            String statusText = statusText(status);
            sb.append("HTTP/1.1 ").append(status).append(" ").append(statusText).append("\r\n");

            // ETag header if present
            Object etagHdr = r.getHeaders().getFirst("ETag");
            if (etagHdr != null) {
                sb.append("ETag: ").append(etagHdr).append("\r\n");
            }

            // Body: only include for error responses (4xx/5xx).
            // Success sub-responses must NOT carry a JSON body — several SDKs
            // (Node @azure/data-tables) call their error handler on ANY JSON body
            // regardless of the HTTP status, so an entity payload in a 201 response
            // would be misinterpreted as a failure.
            Object entity = r.getEntity();
            if (entity != null && status >= 400) {
                String bodyStr;
                if (entity instanceof String s) {
                    bodyStr = s;
                } else {
                    try {
                        bodyStr = objectMapper.writeValueAsString(entity);
                    } catch (Exception e) {
                        bodyStr = entity.toString();
                    }
                }
                if (!bodyStr.isBlank()) {
                    sb.append("Content-Type: application/json\r\n");
                    sb.append("\r\n");
                    sb.append(bodyStr).append("\r\n");
                } else {
                    sb.append("\r\n");
                }
            } else {
                sb.append("\r\n");
            }
        }

        sb.append("--").append(changesetBoundary).append("--\r\n");
        sb.append("--").append(batchBoundary).append("--\r\n");

        return Response.status(202)
                .type("multipart/mixed; boundary=" + batchBoundary)
                .entity(sb.toString())
                .build();
    }

    /**
     * Build an error batch response for a failed operation.
     */
    private Response buildErrorBatchResponse(int failedIdx, Response failedResponse) {
        String batchId = UUID.randomUUID().toString().replace("-", "");
        String changesetId = UUID.randomUUID().toString().replace("-", "");
        String batchBoundary = "batchresponse_" + batchId;
        String changesetBoundary = "changesetresponse_" + changesetId;

        int status = failedResponse.getStatus();
        String statusText = statusText(status);

        Object entity = failedResponse.getEntity();
        String bodyStr = "";
        if (entity != null) {
            if (entity instanceof String s) {
                bodyStr = s;
            } else {
                try {
                    bodyStr = objectMapper.writeValueAsString(entity);
                } catch (Exception e) {
                    bodyStr = entity.toString();
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("--").append(batchBoundary).append("\r\n");
        sb.append("Content-Type: multipart/mixed; boundary=").append(changesetBoundary).append("\r\n");
        sb.append("\r\n");
        sb.append("--").append(changesetBoundary).append("\r\n");
        sb.append("Content-Type: application/http\r\n");
        sb.append("Content-Transfer-Encoding: binary\r\n");
        sb.append("\r\n");
        sb.append("HTTP/1.1 ").append(status).append(" ").append(statusText).append("\r\n");
        if (!bodyStr.isBlank()) {
            sb.append("Content-Type: application/json\r\n");
            sb.append("\r\n");
            sb.append(bodyStr).append("\r\n");
        } else {
            sb.append("\r\n");
        }
        sb.append("--").append(changesetBoundary).append("--\r\n");
        sb.append("--").append(batchBoundary).append("--\r\n");

        return Response.status(202)
                .type("multipart/mixed; boundary=" + batchBoundary)
                .entity(sb.toString())
                .build();
    }

    // -------------------------------------------------------------------------
    // MIME parsing helpers for $batch
    // -------------------------------------------------------------------------

    /**
     * Extract the boundary parameter from a Content-Type header value.
     * e.g. "multipart/mixed; boundary=abc123" → "abc123"
     */
    private String extractBoundary(String contentType) {
        if (contentType == null) return null;
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase().startsWith("boundary=")) {
                String boundary = trimmed.substring("boundary=".length()).trim();
                // Strip surrounding quotes if present
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        return null;
    }

    /**
     * Split a multipart body on the given boundary.
     * Splits on "--boundary" (RFC 2046: the first boundary has no preceding CRLF,
     * but subsequent ones do — we handle both by splitting on the bare delimiter
     * and stripping the leading CRLF/LF from each resulting part).
     */
    private String[] splitOnBoundary(String body, String boundary) {
        String[] parts = body.split(java.util.regex.Pattern.quote("--" + boundary), -1);
        // Strip leading CRLF or LF from each part (artifact of the delimiter encoding)
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.startsWith("\r\n")) parts[i] = p.substring(2);
            else if (p.startsWith("\n")) parts[i] = p.substring(1);
        }
        return parts;
    }

    /**
     * Extract the value of a named header from a MIME part string.
     * The part starts with MIME headers, one per line.
     */
    private String extractHeaderFromPart(String part, String headerName) {
        String[] lines = part.split("\r\n|\n", -1);
        for (String line : lines) {
            if (line.isBlank()) break;
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String name = line.substring(0, colonIdx).trim();
                if (name.equalsIgnoreCase(headerName)) {
                    return line.substring(colonIdx + 1).trim();
                }
            }
        }
        return null;
    }

    /**
     * Extract the body of a MIME part (the content after the first blank line).
     */
    private String extractBodyAfterHeaders(String part) {
        // Try \r\n\r\n first
        int idx = part.indexOf("\r\n\r\n");
        if (idx >= 0) {
            return part.substring(idx + 4);
        }
        // Fallback to \n\n
        idx = part.indexOf("\n\n");
        if (idx >= 0) {
            return part.substring(idx + 2);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    public void clearAll() {
        store.clear();
    }

    private String statusText(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 412 -> "Precondition Failed";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            default  -> "Unknown";
        };
    }

    private String extractTableNameFromTablesPath(String path) {
        int start = path.indexOf("'");
        int end = path.lastIndexOf("'");
        if (start != -1 && end != -1 && start < end) {
            return path.substring(start + 1, end);
        }
        return "";
    }

    private String extractValue(String part, String key) {
        int start = part.indexOf(key + "='");
        if (start == -1) return "";
        start += key.length() + 2;
        int end = part.indexOf("'", start);
        if (end == -1) return "";
        return part.substring(start, end);
    }

    private static String nsKey(String accountName, String tableName) {
        return NS_PREFIX + accountName + "/" + tableName;
    }

    private static String objKey(String accountName, String tableName, String key) {
        return accountName + "/" + tableName + "/" + key;
    }
}
