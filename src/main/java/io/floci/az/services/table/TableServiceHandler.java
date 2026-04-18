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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Locale;
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
                .header("Preference-Applied", "return-no-content")
                .header("DataServiceVersion", "3.0;")
                .build();
    }

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

            return Response.status(Response.Status.CREATED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(entity)
                    .header("ETag", etag)
                    .build();
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
            return Response.ok(objectMapper.readValue(object.get().data(), Map.class))
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }

    private Response queryEntities(AzureRequest request, String tableName) {
        String keyPrefix = objKey(request.accountName(), tableName, "");
        List<Map<String, Object>> entities = store.scan(k -> k.startsWith(keyPrefix)).stream()
                .map(so -> {
                    try {
                        return (Map<String, Object>) objectMapper.readValue(so.data(), Map.class);
                    } catch (IOException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return Response.ok(new TableModel.EntityListResponse(entities))
                .type(MediaType.APPLICATION_JSON).build();
    }

    private Response deleteEntity(AzureRequest request, String tableName, String pkRkPart) {
        String pk = extractValue(pkRkPart, "PartitionKey");
        String rk = extractValue(pkRkPart, "RowKey");
        store.delete(objKey(request.accountName(), tableName, pk + "_" + rk));
        return Response.noContent().build();
    }

    private Response updateEntity(AzureRequest request, String tableName, String pkRkPart) {
        try {
            Map<String, Object> entity = new LinkedHashMap<>(objectMapper.readValue(request.bodyStream(), Map.class));
            String pk = (String) entity.get("PartitionKey");
            String rk = (String) entity.get("RowKey");
            if (pk == null || rk == null) {
                pk = extractValue(pkRkPart, "PartitionKey");
                rk = extractValue(pkRkPart, "RowKey");
                entity.put("PartitionKey", pk);
                entity.put("RowKey", rk);
            }
            if (pk.isEmpty() || rk.isEmpty()) {
                return new AzureErrorResponse("PropertiesNeedValue",
                        "PartitionKey and RowKey are required.")
                        .toJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }
            String key = pk + "_" + rk;
            String etag = UUID.randomUUID().toString();
            entity.put("Timestamp", ISO_TIMESTAMP.format(Instant.now()));

            store.put(objKey(request.accountName(), tableName, key),
                    new StoredObject(key, objectMapper.writeValueAsBytes(entity), Map.of(), Instant.now(), etag));

            return Response.noContent()
                    .header("ETag", etag)
                    .build();
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }

    public void clearAll() {
        store.clear();
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
