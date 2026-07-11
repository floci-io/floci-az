package io.floci.az.services.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmProviderService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class MonitorHandler implements AzureServiceHandler, Resettable, ArmProviderService {

    private static final Logger LOG = Logger.getLogger(MonitorHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern WORKSPACE_PATTERN = Pattern.compile(
        "subscriptions/([^/]+)/resourceGroups/([^/]+)/providers/Microsoft\\.OperationalInsights/workspaces/([^/?]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern DCE_PATTERN = Pattern.compile(
        "subscriptions/([^/]+)/resourceGroups/([^/]+)/providers/Microsoft\\.Insights/dataCollectionEndpoints/([^/?]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern DCR_PATTERN = Pattern.compile(
        "subscriptions/([^/]+)/resourceGroups/([^/]+)/providers/Microsoft\\.Insights/dataCollectionRules/([^/?]+)", Pattern.CASE_INSENSITIVE);

    private final StorageBackend<String, StoredObject> store;
    private final EmulatorConfig config;

    @Inject
    public MonitorHandler(StorageFactory factory, EmulatorConfig config) {
        this.store = factory.create("monitor");
        this.config = config;
    }

    @Override
    public String getServiceType() {
        return "monitor";
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().monitor().enabled();
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return "monitor".equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest request) {
        String path = request.resourcePath();
        String method = request.method().toUpperCase();

        LOG.debugf("MonitorHandler data-plane %s /%s", method, path);

        if (path.startsWith("dataCollectionRules/")) {
            return handleIngestion(request);
        } else if (path.startsWith("v1/workspaces/")) {
            return handleQuery(request);
        }

        return Response.status(404).entity("Not Found").build();
    }

    @Override
    public Set<String> providerNamespaces() {
        return Set.of("Microsoft.OperationalInsights", "Microsoft.Insights");
    }

    @Override
    public Response handleArm(AzureRequest req, String path, String method, String sub) {
        LOG.debugf("MonitorHandler ARM-plane %s /%s", method, path);

        if (path.contains("/providers/Microsoft.OperationalInsights/workspaces/")) {
            return handleWorkspaceArm(req, path, method, sub);
        } else if (path.contains("/providers/Microsoft.Insights/dataCollectionEndpoints/")) {
            return handleDceArm(req, path, method, sub);
        } else if (path.contains("/providers/Microsoft.Insights/dataCollectionRules/")) {
            return handleDcrArm(req, path, method, sub);
        }
        return Response.status(404).entity("Provider resource not found").build();
    }

    // -------------------------------------------------------------------------
    // Workspace ARM CRUD
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Response handleWorkspaceArm(AzureRequest req, String path, String method, String sub) {
        Matcher m = WORKSPACE_PATTERN.matcher(path);
        if (!m.find()) {
            return Response.status(400).entity("Invalid workspace path").build();
        }
        String rg = m.group(2);
        String name = m.group(3);
        String key = String.format("workspace/%s/%s/%s", sub, rg, name);

        if ("PUT".equals(method)) {
            Map<String, Object> body = parseBody(req);
            String location = (String) body.getOrDefault("location", "eastus");
            Map<String, Object> props = (Map<String, Object>) body.getOrDefault("properties", new HashMap<>());
            String customerId = (String) props.get("customerId");
            if (customerId == null || customerId.isBlank()) {
                customerId = UUID.randomUUID().toString();
            }

            Map<String, Object> workspace = new LinkedHashMap<>();
            workspace.put("id", "/" + path);
            workspace.put("name", name);
            workspace.put("type", "Microsoft.OperationalInsights/workspaces");
            workspace.put("location", location);
            
            Map<String, Object> newProps = new LinkedHashMap<>(props);
            newProps.put("provisioningState", "Succeeded");
            newProps.put("customerId", customerId);
            workspace.put("properties", newProps);

            store.put(key, new StoredObject(key, toBytes(workspace), Map.of("customerId", customerId), Instant.now(), UUID.randomUUID().toString()));
            
            String lookupKey = "workspaceId/" + customerId;
            store.put(lookupKey, new StoredObject(lookupKey, key.getBytes(StandardCharsets.UTF_8), Map.of(), Instant.now(), ""));

            return Response.status(201).entity(workspace).type(MediaType.APPLICATION_JSON).build();
        } else if ("GET".equals(method)) {
            Optional<StoredObject> obj = store.get(key);
            if (obj.isEmpty()) {
                return Response.status(404).entity("Workspace not found").build();
            }
            return Response.ok(parseStoredData(obj.get())).type(MediaType.APPLICATION_JSON).build();
        } else if ("DELETE".equals(method)) {
            Optional<StoredObject> obj = store.get(key);
            if (obj.isPresent()) {
                Map<String, Object> workspace = parseStoredData(obj.get());
                Map<String, Object> props = (Map<String, Object>) workspace.get("properties");
                if (props != null) {
                    String customerId = (String) props.get("customerId");
                    if (customerId != null) {
                        store.delete("workspaceId/" + customerId);
                    }
                }
                store.delete(key);
            }
            return Response.ok().build();
        }
        return Response.status(405).build();
    }

    // -------------------------------------------------------------------------
    // Data Collection Endpoints (DCE) ARM CRUD
    // -------------------------------------------------------------------------

    private Response handleDceArm(AzureRequest req, String path, String method, String sub) {
        Matcher m = DCE_PATTERN.matcher(path);
        if (!m.find()) {
            return Response.status(400).entity("Invalid DCE path").build();
        }
        String rg = m.group(2);
        String name = m.group(3);
        String key = String.format("dce/%s/%s/%s", sub, rg, name);

        if ("PUT".equals(method)) {
            Map<String, Object> body = parseBody(req);
            String location = (String) body.getOrDefault("location", "eastus");
            
            Map<String, Object> dce = new LinkedHashMap<>();
            dce.put("id", "/" + path);
            dce.put("name", name);
            dce.put("type", "Microsoft.Insights/dataCollectionEndpoints");
            dce.put("location", location);

            String ingestUrl = config.effectiveBaseUrl();

            Map<String, Object> props = new LinkedHashMap<>();
            props.put("provisioningState", "Succeeded");
            props.put("logsIngestion", Map.of("endpoint", ingestUrl));
            props.put("configurationAccess", Map.of("endpoint", ingestUrl));
            dce.put("properties", props);

            store.put(key, new StoredObject(key, toBytes(dce), Map.of(), Instant.now(), UUID.randomUUID().toString()));
            return Response.status(201).entity(dce).type(MediaType.APPLICATION_JSON).build();
        } else if ("GET".equals(method)) {
            Optional<StoredObject> obj = store.get(key);
            if (obj.isEmpty()) {
                return Response.status(404).entity("DCE not found").build();
            }
            return Response.ok(parseStoredData(obj.get())).type(MediaType.APPLICATION_JSON).build();
        } else if ("DELETE".equals(method)) {
            store.delete(key);
            return Response.ok().build();
        }
        return Response.status(405).build();
    }

    // -------------------------------------------------------------------------
    // Data Collection Rules (DCR) ARM CRUD
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Response handleDcrArm(AzureRequest req, String path, String method, String sub) {
        Matcher m = DCR_PATTERN.matcher(path);
        if (!m.find()) {
            return Response.status(400).entity("Invalid DCR path").build();
        }
        String rg = m.group(2);
        String name = m.group(3);
        String key = String.format("dcr/%s/%s/%s", sub, rg, name);

        if ("PUT".equals(method)) {
            Map<String, Object> body = parseBody(req);
            String location = (String) body.getOrDefault("location", "eastus");
            Map<String, Object> props = (Map<String, Object>) body.getOrDefault("properties", new HashMap<>());
            String immutableId = (String) props.get("immutableId");
            if (immutableId == null || immutableId.isBlank()) {
                immutableId = "dcr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            }

            Map<String, Object> dcr = new LinkedHashMap<>();
            dcr.put("id", "/" + path);
            dcr.put("name", name);
            dcr.put("type", "Microsoft.Insights/dataCollectionRules");
            dcr.put("location", location);

            Map<String, Object> newProps = new LinkedHashMap<>(props);
            newProps.put("provisioningState", "Succeeded");
            newProps.put("immutableId", immutableId);
            dcr.put("properties", newProps);

            store.put(key, new StoredObject(key, toBytes(dcr), Map.of("immutableId", immutableId), Instant.now(), UUID.randomUUID().toString()));
            
            String lookupKey = "dcrId/" + immutableId;
            store.put(lookupKey, new StoredObject(lookupKey, key.getBytes(StandardCharsets.UTF_8), Map.of(), Instant.now(), ""));

            return Response.status(201).entity(dcr).type(MediaType.APPLICATION_JSON).build();
        } else if ("GET".equals(method)) {
            Optional<StoredObject> obj = store.get(key);
            if (obj.isEmpty()) {
                return Response.status(404).entity("DCR not found").build();
            }
            return Response.ok(parseStoredData(obj.get())).type(MediaType.APPLICATION_JSON).build();
        } else if ("DELETE".equals(method)) {
            Optional<StoredObject> obj = store.get(key);
            if (obj.isPresent()) {
                Map<String, Object> dcr = parseStoredData(obj.get());
                Map<String, Object> props = (Map<String, Object>) dcr.get("properties");
                if (props != null) {
                    String immutableId = (String) props.get("immutableId");
                    if (immutableId != null) {
                        store.delete("dcrId/" + immutableId);
                    }
                }
                store.delete(key);
            }
            return Response.ok().build();
        }
        return Response.status(405).build();
    }

    // -------------------------------------------------------------------------
    // Logs Ingestion API
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Response handleIngestion(AzureRequest req) {
        String path = req.resourcePath();
        String[] parts = path.split("/");
        if (parts.length < 4) {
            return Response.status(400).entity("Invalid ingestion path").build();
        }
        String dcrId = parts[1];
        String stream = parts[3];

        // Find DCR key from lookup
        Optional<StoredObject> dcrLookup = store.get("dcrId/" + dcrId);
        if (dcrLookup.isEmpty()) {
            return Response.status(404).entity("DCR not found with immutableId: " + dcrId).build();
        }
        String dcrKey = new String(dcrLookup.get().data(), StandardCharsets.UTF_8);
        Optional<StoredObject> dcrObj = store.get(dcrKey);
        if (dcrObj.isEmpty()) {
            return Response.status(404).entity("DCR not found").build();
        }

        Map<String, Object> dcr = parseStoredData(dcrObj.get());
        Map<String, Object> props = (Map<String, Object>) dcr.get("properties");
        if (props == null) {
            return Response.status(400).entity("DCR has no properties").build();
        }

        // Find workspace resource ID from destinations
        Map<String, Object> destinations = (Map<String, Object>) props.get("destinations");
        if (destinations == null) {
            return Response.status(400).entity("DCR has no destinations").build();
        }
        List<Map<String, Object>> logAnalytics = (List<Map<String, Object>>) destinations.get("logAnalytics");
        if (logAnalytics == null || logAnalytics.isEmpty()) {
            return Response.status(400).entity("DCR has no Log Analytics destinations").build();
        }
        String workspaceResourceId = (String) logAnalytics.get(0).get("workspaceResourceId");
        if (workspaceResourceId == null) {
            return Response.status(400).entity("DCR has no workspaceResourceId").build();
        }

        // Get workspace ID (customerId) from the workspace resource ID
        String workspaceStoreKey = mapResourceIdToStoreKey(workspaceResourceId);
        Optional<StoredObject> workspaceObj = store.get(workspaceStoreKey);
        if (workspaceObj.isEmpty()) {
            return Response.status(404).entity("Workspace not found: " + workspaceResourceId).build();
        }
        Map<String, Object> workspace = parseStoredData(workspaceObj.get());
        Map<String, Object> wsProps = (Map<String, Object>) workspace.get("properties");
        String workspaceId = wsProps != null ? (String) wsProps.get("customerId") : null;
        if (workspaceId == null) {
            workspaceId = (String) workspace.get("name");
        }

        // Parse log records array from body stream
        List<Map<String, Object>> records;
        try {
            records = MAPPER.readValue(req.bodyStream(), new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException e) {
            return Response.status(400).entity("Failed to parse request body as log records: " + e.getMessage()).build();
        }

        Instant now = Instant.now();
        for (Map<String, Object> record : records) {
            Object timeObj = record.get("TimeGenerated");
            if (timeObj == null) {
                for (Map.Entry<String, Object> entry : record.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase("TimeGenerated")) {
                        timeObj = entry.getValue();
                        break;
                    }
                }
            }

            Instant recordTime = now;
            if (timeObj != null) {
                try {
                    recordTime = Instant.parse(timeObj.toString());
                } catch (Exception e) {
                    // fallback to now
                }
            } else {
                record.put("TimeGenerated", now.toString());
            }

            // Key format: workspaceId/stream/timestamp/id
            String recordKey = String.format("%s/%s/%s/%s", workspaceId, stream, recordTime.toString(), UUID.randomUUID().toString());
            store.put(recordKey, new StoredObject(recordKey, toBytes(record), Map.of(), recordTime, ""));
        }

        return Response.noContent().build();
    }

    private String mapResourceIdToStoreKey(String resourceId) {
        String clean = resourceId;
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        String[] parts = clean.split("/");
        String sub = "unknown";
        String rg = "unknown";
        String name = "unknown";
        for (int i = 0; i < parts.length - 1; i++) {
            if ("subscriptions".equalsIgnoreCase(parts[i])) sub = parts[i+1];
            if ("resourceGroups".equalsIgnoreCase(parts[i])) rg = parts[i+1];
            if ("workspaces".equalsIgnoreCase(parts[i])) name = parts[i+1];
        }
        return String.format("workspace/%s/%s/%s", sub, rg, name);
    }

    // -------------------------------------------------------------------------
    // Log Query API
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Response handleQuery(AzureRequest req) {
        String path = req.resourcePath();
        String[] parts = path.split("/");
        if (parts.length < 4) {
            return Response.status(400).entity("Invalid query path").build();
        }
        String workspaceId = parts[2];

        // Resolve workspace to get the actual customerId GUID if workspaceId is name/resourceId
        Optional<StoredObject> wsLookup = store.get("workspaceId/" + workspaceId);
        if (wsLookup.isEmpty()) {
            // Check if workspaceId is name
            List<StoredObject> workspaces = store.scan(k -> k.startsWith("workspace/") && k.endsWith("/" + parts[2]));
            if (!workspaces.isEmpty()) {
                Map<String, Object> ws = parseStoredData(workspaces.get(0));
                Map<String, Object> props = (Map<String, Object>) ws.get("properties");
                if (props != null && props.containsKey("customerId")) {
                    workspaceId = (String) props.get("customerId");
                }
            }
        }

        Map<String, Object> queryBody = parseBody(req);
        String queryStr = (String) queryBody.get("query");
        String timespan = (String) queryBody.get("timespan");

        if (queryStr == null || queryStr.isBlank()) {
            return Response.status(400).entity("Missing query in request body").build();
        }

        String[] qParts = queryStr.split("\\|");
        String tableName = qParts[0].trim();

        // Retrieve logs matching workspaceId/tableName
        final String finalWorkspaceId = workspaceId;
        final String cleanTable = tableName.startsWith("Custom-") ? tableName.substring(7) : tableName;

        List<StoredObject> matchedLogs = store.scan(k -> {
            String[] kp = k.split("/");
            if (kp.length < 4) return false;
            String kWorkspaceId = kp[0];
            String kStream = kp[1];
            if (!kWorkspaceId.equalsIgnoreCase(finalWorkspaceId)) return false;
            
            String cleanStream = kStream.startsWith("Custom-") ? kStream.substring(7) : kStream;
            return cleanStream.equalsIgnoreCase(cleanTable) || kStream.equalsIgnoreCase(tableName);
        });

        List<Map<String, Object>> records = new ArrayList<>();
        for (StoredObject obj : matchedLogs) {
            records.add(parseStoredData(obj));
        }

        records = KqlEngine.filterByTimespan(records, timespan);

        KqlEngine.KqlResult kqlResult;
        try {
            kqlResult = KqlEngine.execute(queryStr, records);
        } catch (Exception e) {
            return Response.status(400).entity("Failed to execute KQL query: " + e.getMessage()).build();
        }

        Map<String, Object> tableMap = new LinkedHashMap<>();
        tableMap.put("name", "PrimaryResult");

        List<Map<String, String>> columnsList = new ArrayList<>();
        for (String col : kqlResult.columns) {
            Map<String, String> colMap = new LinkedHashMap<>();
            colMap.put("name", col);
            colMap.put("type", determineColType(col, kqlResult.rows, kqlResult.columns.indexOf(col)));
            columnsList.add(colMap);
        }
        tableMap.put("columns", columnsList);
        tableMap.put("rows", kqlResult.rows);

        Map<String, Object> responseMap = new LinkedHashMap<>();
        responseMap.put("tables", List.of(tableMap));

        return Response.ok(responseMap).type(MediaType.APPLICATION_JSON).build();
    }

    private String determineColType(String colName, List<List<Object>> rows, int colIdx) {
        if ("TimeGenerated".equalsIgnoreCase(colName)) {
            return "datetime";
        }
        for (List<Object> row : rows) {
            if (row.size() > colIdx) {
                Object val = row.get(colIdx);
                if (val != null) {
                    if (val instanceof Boolean) {
                        return "bool";
                    } else if (val instanceof Integer || val instanceof Long) {
                        return "long";
                    } else if (val instanceof Number) {
                        return "real";
                    }
                }
            }
        }
        return "string";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private byte[] toBytes(Object val) {
        try {
            return MAPPER.writeValueAsBytes(val);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseStoredData(StoredObject obj) {
        try {
            return MAPPER.readValue(obj.data(), Map.class);
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    private Map<String, Object> parseBody(AzureRequest req) {
        return ArmJson.parseBodyStrict(req);
    }

    public void clearAll() {
        store.clear();
    }
}
