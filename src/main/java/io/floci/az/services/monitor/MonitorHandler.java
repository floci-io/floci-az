package io.floci.az.services.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.monitor.activitylog.ActivityLogEndpoint;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmProviderService;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.ResourceIndexContributor;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.monitor.MonitorModels.MonitorResource;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

@ApplicationScoped
public class MonitorHandler implements AzureServiceHandler, Resettable, ArmProviderService,
        ResourceIndexContributor {

    private static final Logger LOG = Logger.getLogger(MonitorHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern WORKSPACE_PATTERN = Pattern.compile(
        "subscriptions/([^/]+)/resourceGroups/([^/]+)/providers/Microsoft\\.OperationalInsights/workspaces/([^/?]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern WORKSPACE_KEYS_ACTION = Pattern.compile(
        "/workspaces/[^/]+/(sharedKeys|listKeys)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern DCE_PATTERN = Pattern.compile(
        "subscriptions/([^/]+)/resourceGroups/([^/]+)/providers/Microsoft\\.Insights/dataCollectionEndpoints/([^/?]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern DCR_PATTERN = Pattern.compile(
        "subscriptions/([^/]+)/resourceGroups/([^/]+)/providers/Microsoft\\.Insights/dataCollectionRules/([^/?]+)", Pattern.CASE_INSENSITIVE);

    /** Matches any resource path with a diagnostic-settings extension resource appended. */
    private static final Pattern DIAG_SETTINGS_PATTERN = Pattern.compile(
        "(.+)/providers/microsoft\\.insights/diagnosticsettings(?:/([^/?]+))?",
        Pattern.CASE_INSENSITIVE);

    /** The workspaces, data collection endpoints and rules a resource group or subscription lists. */
    private static final Pattern COLLECTION_PATTERN = Pattern.compile(
        "subscriptions/([^/]+)(?:/resourceGroups/([^/]+))?/providers/"
            + "(Microsoft\\.OperationalInsights/workspaces|Microsoft\\.Insights/dataCollectionEndpoints"
            + "|Microsoft\\.Insights/dataCollectionRules)/?(?:\\?.*)?",
        Pattern.CASE_INSENSITIVE);

    static final String WORKSPACE_TYPE = "Microsoft.OperationalInsights/workspaces";
    static final String DCE_TYPE = "Microsoft.Insights/dataCollectionEndpoints";
    static final String DCR_TYPE = "Microsoft.Insights/dataCollectionRules";
    static final String DIAGNOSTIC_SETTINGS_TYPE = "Microsoft.Insights/diagnosticSettings";

    /**
     * Before the stores, the handler kept lookups from a workspace's customerId and a rule's immutableId to their
     * keys. Nothing reads them now; deleting the resource removes its old lookup with it.
     */
    private static final String LEGACY_WORKSPACE_LOOKUP = "workspaceId/";
    private static final String LEGACY_RULE_LOOKUP = "dcrId/";

    /**
     * The {@code monitor} backend holds the ARM resources, each type in its own store over canonical keys, and the
     * ingested log records under {@code {customerId}/{stream}/{time}/{uuid}}. Before the stores, the resources were
     * kept under {@code workspace/}, {@code dce/} and {@code dcr/} plus the raw-case {@code sub/rg/name}, and under
     * {@code diag/} plus the lowercased resource URI and setting name; those entries are read as they are and move to
     * their canonical key on their next write.
     */
    private final StorageBackend<String, StoredObject> store;
    private final ArmResourceStore<MonitorResource> workspaces;
    private final ArmResourceStore<MonitorResource> endpoints;
    private final ArmResourceStore<MonitorResource> rules;
    private final ArmResourceStore<MonitorResource> diagnosticSettings;
    private final EmulatorConfig config;
    private final long maxDecodedBodyBytes;

    @Inject
    ActivityLogEndpoint activityLog;

    @Inject
    public MonitorHandler(StorageFactory factory, EmulatorConfig config,
                          @ConfigProperty(name = "quarkus.http.limits.max-body-size", defaultValue = "2G")
                          MemorySize maxBodySize) {
        this.store = factory.create("monitor");
        this.workspaces = ArmResourceStore.create(factory, "monitor", WORKSPACE_TYPE, MonitorResource.class,
            MonitorResource::id, key -> key.startsWith("workspace/"));
        this.endpoints = ArmResourceStore.create(factory, "monitor", DCE_TYPE, MonitorResource.class,
            MonitorResource::id, key -> key.startsWith("dce/"));
        this.rules = ArmResourceStore.create(factory, "monitor", DCR_TYPE, MonitorResource.class,
            MonitorResource::id, key -> key.startsWith("dcr/"));
        this.diagnosticSettings = ArmResourceStore.create(factory, "monitor", DIAGNOSTIC_SETTINGS_TYPE,
            MonitorResource.class, MonitorResource::id, key -> key.startsWith("diag/"));
        this.config = config;
        this.maxDecodedBodyBytes = maxBodySize.asLongValue();
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
    public Response handle(AzureRequest request) {
        String path = request.resourcePath();
        String method = request.method().toUpperCase();

        LOG.debugf("MonitorHandler %s /%s", method, path);

        if (path.startsWith("dataCollectionRules/")) {
            return handleIngestion(request);
        } else if (path.startsWith("v1/workspaces/")) {
            return handleQuery(request);
        }

        // ARM-plane calls routed via AzureRoutingFilter with serviceType=monitor
        if (isDiagnosticSettingsPath(path)) {
            return handleDiagnosticSettingsArm(request, path, method.toUpperCase());
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

        if (ActivityLogEndpoint.matches(path)) {
            if (!config.services().monitor().activityLog().enabled()) {
                return Response.status(404).entity("Provider resource not found").build();
            }
            return activityLog.list(req, sub);
        }
        Matcher collection = COLLECTION_PATTERN.matcher(path);
        if (collection.matches()) {
            return "GET".equals(method)
                ? listCollection(collection.group(3), new ArmScope(collection.group(1), collection.group(2)))
                : Response.status(405).build();
        }
        if (path.contains("/providers/Microsoft.OperationalInsights/workspaces/")) {
            return handleWorkspaceArm(req, path, method);
        } else if (path.contains("/providers/Microsoft.Insights/dataCollectionEndpoints/")) {
            return handleDceArm(req, path, method);
        } else if (path.contains("/providers/Microsoft.Insights/dataCollectionRules/")) {
            return handleDcrArm(req, path, method);
        } else if (isDiagnosticSettingsPath(path)) {
            return handleDiagnosticSettingsArm(req, path, method.toUpperCase());
        }
        return Response.status(404).entity("Provider resource not found").build();
    }

    /** The workspaces, data collection endpoints or rules in a resource group, or in a whole subscription. */
    private Response listCollection(String type, ArmScope scope) {
        List<MonitorResource> value = storeOf(type).list(scope).stream().map(ArmResourceStore.Stored::value).toList();
        return Response.ok(Map.of("value", value)).type(MediaType.APPLICATION_JSON).build();
    }

    private ArmResourceStore<MonitorResource> storeOf(String type) {
        if (WORKSPACE_TYPE.equalsIgnoreCase(type)) {
            return workspaces;
        }
        return DCE_TYPE.equalsIgnoreCase(type) ? endpoints : rules;
    }

    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return indexEntries(new ArmScope(sub, rg));
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return indexEntries(new ArmScope(sub, null));
    }

    @Override
    public boolean indexEnabled() {
        return config.services().monitor().enabled();
    }

    /** Joined the index with the resource-group cascade; listed only under {@code resource-group-delete}. */
    @Override
    public boolean inDefaultListing() {
        return false;
    }

    /**
     * Workspaces, data collection endpoints and rules in a subscription, or in one of its resource groups.
     * Diagnostic settings are extension resources and are not listed.
     */
    private List<Map<String, Object>> indexEntries(ArmScope scope) {
        return Stream.of(workspaces, endpoints, rules)
                .flatMap(resources -> resources.list(scope).stream())
                .map(ArmResourceStore.Stored::value)
                .map(resource -> ArmResources.indexEntry(resource.id(), resource.name(), resource.type(),
                    resource.location(), null))
                .toList();
    }

    /**
     * Returns true when the path contains an Azure diagnostic-settings extension resource
     * ({@code /providers/microsoft.insights/diagnosticSettings[/{name}]}).
     * The check is case-insensitive to match both the az CLI and SDKs.
     */
    public static boolean isDiagnosticSettingsPath(String path) {
        if (path == null) return false;
        return DIAG_SETTINGS_PATTERN.matcher(path).find();
    }

    /** The id of the resource {@code matched} addresses: the matched part of the path, in the client's casing. */
    private static ArmResourceId resourceId(String matched) {
        return ArmResourceId.parse("/" + matched).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyProperties(Map<String, Object> body) {
        return body.get("properties") instanceof Map<?, ?> props
            ? new LinkedHashMap<>((Map<String, Object>) props) : new LinkedHashMap<>();
    }

    private static String bodyLocation(Map<String, Object> body) {
        return (String) body.getOrDefault("location", "eastus");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // -------------------------------------------------------------------------
    // Workspace ARM CRUD
    // -------------------------------------------------------------------------

    private Response handleWorkspaceArm(AzureRequest req, String path, String method) {
        Matcher m = WORKSPACE_PATTERN.matcher(path);
        if (!m.find()) {
            return Response.status(400).entity("Invalid workspace path").build();
        }
        ArmResourceId id = resourceId(m.group(0));

        if (WORKSPACE_KEYS_ACTION.matcher(path).find()) {
            return "POST".equals(method) ? sharedKeys(id) : Response.status(405).build();
        }

        if ("PUT".equals(method)) {
            Map<String, Object> body = parseBody(req);
            Map<String, Object> props = bodyProperties(body);
            MonitorResource workspace = workspaces.createOrUpdate(id, existing -> {
                // customerId is read-only: a redeploy keeps the one the workspace was created with
                String customerId = existing.map(ws -> ws.property("customerId"))
                    .orElse(props.get("customerId") instanceof String text ? text : null);
                if (isBlank(customerId)) {
                    customerId = UUID.randomUUID().toString();
                }
                Map<String, Object> newProps = new LinkedHashMap<>(props);
                newProps.put("provisioningState", "Succeeded");
                newProps.put("customerId", customerId);
                return new MonitorResource(existing.map(MonitorResource::id).orElse(id.id()),
                    existing.map(MonitorResource::name).orElse(id.name()), WORKSPACE_TYPE, bodyLocation(body),
                    newProps);
            }).stored().value();
            return Response.status(201).entity(workspace).type(MediaType.APPLICATION_JSON).build();
        } else if ("GET".equals(method)) {
            Optional<ArmResourceStore.Stored<MonitorResource>> workspace = workspaces.get(id);
            if (workspace.isEmpty()) {
                return Response.status(404).entity("Workspace not found").build();
            }
            return Response.ok(workspace.get().value()).type(MediaType.APPLICATION_JSON).build();
        } else if ("DELETE".equals(method)) {
            workspaces.delete(id).map(ArmResourceStore.Stored::value)
                .map(workspace -> workspace.property("customerId"))
                .ifPresent(customerId -> store.delete(LEGACY_WORKSPACE_LOOKUP + customerId));
            return Response.ok().build();
        }
        return Response.status(405).build();
    }

    /** The workspace's shared keys, derived from its customer id so they hold for the workspace's life. */
    private Response sharedKeys(ArmResourceId id) {
        Optional<ArmResourceStore.Stored<MonitorResource>> workspace = workspaces.get(id);
        if (workspace.isEmpty()) {
            return Response.status(404)
                .entity(Map.of("error", Map.of("code", "ResourceNotFound", "message", "Workspace not found")))
                .type(MediaType.APPLICATION_JSON).build();
        }
        String customerId = workspace.get().value().property("customerId");
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("primarySharedKey", sharedKey("primary", customerId));
        keys.put("secondarySharedKey", sharedKey("secondary", customerId));
        return Response.ok(keys).type(MediaType.APPLICATION_JSON).build();
    }

    private static String sharedKey(String slot, String customerId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-512")
                .digest((slot + ":" + customerId).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    // -------------------------------------------------------------------------
    // Data Collection Endpoints (DCE) ARM CRUD
    // -------------------------------------------------------------------------

    private Response handleDceArm(AzureRequest req, String path, String method) {
        Matcher m = DCE_PATTERN.matcher(path);
        if (!m.find()) {
            return Response.status(400).entity("Invalid DCE path").build();
        }
        ArmResourceId id = resourceId(m.group(0));

        if ("PUT".equals(method)) {
            Map<String, Object> body = parseBody(req);
            String ingestUrl = config.effectiveBaseUrl();
            MonitorResource dce = endpoints.createOrUpdate(id, existing -> {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("provisioningState", "Succeeded");
                props.put("logsIngestion", Map.of("endpoint", ingestUrl));
                props.put("configurationAccess", Map.of("endpoint", ingestUrl));
                return new MonitorResource(existing.map(MonitorResource::id).orElse(id.id()),
                    existing.map(MonitorResource::name).orElse(id.name()), DCE_TYPE, bodyLocation(body), props);
            }).stored().value();
            return Response.status(201).entity(dce).type(MediaType.APPLICATION_JSON).build();
        } else if ("GET".equals(method)) {
            Optional<ArmResourceStore.Stored<MonitorResource>> dce = endpoints.get(id);
            if (dce.isEmpty()) {
                return Response.status(404).entity("DCE not found").build();
            }
            return Response.ok(dce.get().value()).type(MediaType.APPLICATION_JSON).build();
        } else if ("DELETE".equals(method)) {
            endpoints.delete(id);
            return Response.ok().build();
        }
        return Response.status(405).build();
    }

    // -------------------------------------------------------------------------
    // Data Collection Rules (DCR) ARM CRUD
    // -------------------------------------------------------------------------

    private Response handleDcrArm(AzureRequest req, String path, String method) {
        Matcher m = DCR_PATTERN.matcher(path);
        if (!m.find()) {
            return Response.status(400).entity("Invalid DCR path").build();
        }
        ArmResourceId id = resourceId(m.group(0));

        if ("PUT".equals(method)) {
            Map<String, Object> body = parseBody(req);
            Map<String, Object> props = bodyProperties(body);
            MonitorResource dcr = rules.createOrUpdate(id, existing -> {
                // immutableId is read-only, and the ingestion URL carries it: a redeploy keeps it
                String immutableId = existing.map(rule -> rule.property("immutableId"))
                    .orElse(props.get("immutableId") instanceof String text ? text : null);
                if (isBlank(immutableId)) {
                    immutableId = "dcr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                }
                Map<String, Object> newProps = new LinkedHashMap<>(props);
                newProps.put("provisioningState", "Succeeded");
                newProps.put("immutableId", immutableId);
                return new MonitorResource(existing.map(MonitorResource::id).orElse(id.id()),
                    existing.map(MonitorResource::name).orElse(id.name()), DCR_TYPE, bodyLocation(body), newProps);
            }).stored().value();
            return Response.status(201).entity(dcr).type(MediaType.APPLICATION_JSON).build();
        } else if ("GET".equals(method)) {
            Optional<ArmResourceStore.Stored<MonitorResource>> dcr = rules.get(id);
            if (dcr.isEmpty()) {
                return Response.status(404).entity("DCR not found").build();
            }
            return Response.ok(dcr.get().value()).type(MediaType.APPLICATION_JSON).build();
        } else if ("DELETE".equals(method)) {
            rules.delete(id).map(ArmResourceStore.Stored::value)
                .map(rule -> rule.property("immutableId"))
                .ifPresent(immutableId -> store.delete(LEGACY_RULE_LOOKUP + immutableId));
            return Response.ok().build();
        }
        return Response.status(405).build();
    }

    // -------------------------------------------------------------------------
    // Logs Ingestion API
    // -------------------------------------------------------------------------

    /**
     * The Logs Ingestion API declares an optional {@code Content-Encoding} header whose only value is
     * {@code gzip}, and both the Java and Python {@code LogsIngestionClient} compress every upload.
     * A gzipped body is held to the same size limit as a plain one once decoded, so a small
     * compressed upload cannot expand past what the HTTP layer would accept.
     */
    static InputStream decodedBody(InputStream body, String contentEncoding, long maxDecodedBytes)
            throws IOException {
        if (body == null) {
            return InputStream.nullInputStream();
        }
        if (contentEncoding != null && contentEncoding.trim().equalsIgnoreCase("gzip")) {
            return new DecodedSizeLimit(new GZIPInputStream(body), maxDecodedBytes);
        }
        return body;
    }

    /** Thrown when a decoded body passes {@link #decodedBody}'s limit. */
    static final class DecodedBodyTooLargeException extends IOException {
        DecodedBodyTooLargeException(long limit) {
            super("decoded request body exceeds " + limit + " bytes");
        }
    }

    private static final class DecodedSizeLimit extends FilterInputStream {
        private final long limit;
        private long count;

        DecodedSizeLimit(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                counted(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                counted(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            counted(skipped);
            return skipped;
        }

        private void counted(long n) throws IOException {
            count += n;
            if (count > limit) {
                throw new DecodedBodyTooLargeException(limit);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Response handleIngestion(AzureRequest req) {
        String path = req.resourcePath();
        String[] parts = path.split("/");
        if (parts.length < 4) {
            return Response.status(400).entity("Invalid ingestion path").build();
        }
        String dcrId = parts[1];
        String stream = parts[3];

        // The ingestion URL names the rule by its immutableId
        Optional<MonitorResource> rule = rules.all().stream().map(ArmResourceStore.Stored::value)
            .filter(candidate -> dcrId.equals(candidate.property("immutableId")))
            .findFirst();
        if (rule.isEmpty()) {
            return Response.status(404).entity("DCR not found with immutableId: " + dcrId).build();
        }

        Map<String, Object> props = rule.get().properties();
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

        // Get workspace ID (customerId) from the workspace resource ID, which compares case-insensitively
        Optional<MonitorResource> workspace = ArmResourceId.parse(workspaceResourceId)
            .filter(id -> WORKSPACE_TYPE.equalsIgnoreCase(id.typePath()))
            .flatMap(workspaces::get)
            .map(ArmResourceStore.Stored::value);
        if (workspace.isEmpty()) {
            return Response.status(404).entity("Workspace not found: " + workspaceResourceId).build();
        }
        String workspaceId = workspace.get().property("customerId");
        if (workspaceId == null) {
            workspaceId = workspace.get().name();
        }

        // Parse log records array from body stream; the ingestion clients gzip every upload
        List<Map<String, Object>> records;
        String encoding = req.headers() == null ? null : req.headers().getHeaderString("Content-Encoding");
        try (InputStream body = decodedBody(req.bodyStream(), encoding, maxDecodedBodyBytes)) {
            records = MAPPER.readValue(body, new TypeReference<List<Map<String, Object>>>() {});
        } catch (DecodedBodyTooLargeException e) {
            return Response.status(413).entity("Failed to parse request body as log records: " + e.getMessage()).build();
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

    // -------------------------------------------------------------------------
    // Log Query API
    // -------------------------------------------------------------------------

    private Response handleQuery(AzureRequest req) {
        String path = req.resourcePath();
        String[] parts = path.split("/");
        if (parts.length < 4) {
            return Response.status(400).entity("Invalid query path").build();
        }
        String workspaceId = parts[2];

        // The path names the workspace by its customerId, under which its records are kept, or by its name
        String requested = parts[2];
        List<MonitorResource> known = workspaces.all().stream().map(ArmResourceStore.Stored::value).toList();
        if (known.stream().noneMatch(ws -> requested.equals(ws.property("customerId")))) {
            Optional<String> byName = known.stream()
                .filter(ws -> requested.equals(ws.name()))
                .map(ws -> ws.property("customerId"))
                .filter(customerId -> customerId != null)
                .findFirst();
            if (byName.isPresent()) {
                workspaceId = byName.get();
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

    // -------------------------------------------------------------------------
    // Diagnostic Settings ARM CRUD
    // -------------------------------------------------------------------------
    // Azure diagnostic settings are an extension resource on any resource:
    //   {resourceUri}/providers/microsoft.insights/diagnosticSettings[/{name}]
    // DSF uses them to route database audit logs to Event Hubs.
    // -------------------------------------------------------------------------

    private Response handleDiagnosticSettingsArm(AzureRequest req, String path, String method) {
        Matcher m = DIAG_SETTINGS_PATTERN.matcher(path);
        if (!m.find()) {
            return Response.status(400).entity("Invalid diagnosticSettings path").build();
        }
        String resourceUri = m.group(1);
        String settingsName = m.group(2);
        Optional<ArmResourceId> target = ArmResourceId.parse(resourceUri);
        if (target.isEmpty()) {
            return Response.status(400).entity("Invalid diagnosticSettings path").build();
        }

        if ("GET".equals(method) && settingsName == null) {
            // The settings extend one resource: the scope of their id is that resource's id
            String targetKey = target.get().canonicalKey();
            List<MonitorResource> settings = diagnosticSettings.all().stream()
                .filter(stored -> stored.id().scope() != null && targetKey.equals(stored.id().scope().canonicalKey()))
                .map(ArmResourceStore.Stored::value)
                .toList();
            return Response.ok(Map.of("value", settings)).type(MediaType.APPLICATION_JSON).build();
        }

        if (settingsName == null) {
            return Response.status(400).entity("diagnosticSettings name is required for this operation").build();
        }

        ArmResourceId id = resourceId(m.group(0));

        return switch (method) {
            case "PUT" -> {
                Map<String, Object> body = parseBody(req);
                Object rawProps = body.get("properties");
                if (rawProps != null && !(rawProps instanceof Map<?, ?>)) {
                    yield Response.status(400).entity("'properties' must be an object").build();
                }
                Map<String, Object> props = bodyProperties(body);
                Map<String, Object> settingProps = buildDiagnosticSettingsProperties(props, resourceUri);
                MonitorResource setting = diagnosticSettings.createOrUpdate(id, existing -> new MonitorResource(
                    existing.map(MonitorResource::id).orElse(id.id()),
                    existing.map(MonitorResource::name).orElse(settingsName),
                    DIAGNOSTIC_SETTINGS_TYPE, null, settingProps)).stored().value();

                LOG.infof("Diagnostic settings configured: resource=%s name=%s", resourceUri, settingsName);
                yield Response.ok(setting).type(MediaType.APPLICATION_JSON).build();
            }
            case "GET" -> {
                Optional<ArmResourceStore.Stored<MonitorResource>> setting = diagnosticSettings.get(id);
                if (setting.isEmpty()) {
                    yield Response.status(404)
                        .entity(Map.of("error", Map.of("code", "ResourceNotFound",
                            "message", "Diagnostic settings '" + settingsName + "' not found")))
                        .build();
                }
                yield Response.ok(setting.get().value()).type(MediaType.APPLICATION_JSON).build();
            }
            case "DELETE" -> {
                diagnosticSettings.delete(id);
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildDiagnosticSettingsProperties(Map<String, Object> inputProps,
                                                                   String resourceUri) {
        Map<String, Object> props = new LinkedHashMap<>();

        // Event Hub destination — the primary DSF audit log sink
        Object ehRuleId = inputProps.get("eventHubAuthorizationRuleId");
        if (ehRuleId != null) props.put("eventHubAuthorizationRuleId", ehRuleId);
        Object ehName = inputProps.get("eventHubName");
        if (ehName != null) props.put("eventHubName", ehName);

        // Log Analytics workspace destination
        Object workspaceId = inputProps.get("workspaceId");
        if (workspaceId != null) props.put("workspaceId", workspaceId);

        // Storage account destination
        Object storageAccountId = inputProps.get("storageAccountId");
        if (storageAccountId != null) props.put("storageAccountId", storageAccountId);

        // Log categories — preserve as-is, defaulting to audit-log categories per resource type
        List<Map<String, Object>> logs;
        Object rawLogs = inputProps.get("logs");
        if (rawLogs instanceof List<?> l) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> typed = (List<Map<String, Object>>) l;
            logs = typed;
        } else {
            logs = defaultLogCategories(resourceUri);
        }
        props.put("logs", logs);

        // Metrics — preserve as-is (DSF only uses logs)
        Object metrics = inputProps.get("metrics");
        props.put("metrics", metrics != null ? metrics : List.of());

        return props;
    }

    private List<Map<String, Object>> defaultLogCategories(String resourceUri) {
        String lc = resourceUri.toLowerCase();
        List<String> categories;
        if (lc.contains("microsoft.dbformysql")) {
            categories = List.of("MySqlAuditLogs", "MySqlSlowLogs");
        } else if (lc.contains("microsoft.dbformariadb")) {
            categories = List.of("MySqlAuditLogs", "MySqlSlowLogs");
        } else if (lc.contains("microsoft.dbforpostgresql")) {
            categories = List.of("PostgreSQLLogs");
        } else if (lc.contains("microsoft.sql")) {
            categories = List.of("SQLSecurityAuditEvents", "SQLInsights", "AutomaticTuning");
        } else {
            categories = List.of("AuditEvent");
        }
        return categories.stream()
            .map(cat -> {
                Map<String, Object> log = new LinkedHashMap<>();
                log.put("category", cat);
                log.put("enabled", true);
                log.put("retentionPolicy", Map.of("enabled", false, "days", 0));
                return log;
            })
            .toList();
    }

    public void clear() {
        workspaces.clear();
        endpoints.clear();
        rules.clear();
        diagnosticSettings.clear();
        store.clear();
    }
}
