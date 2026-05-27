package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.aks.AksModels.AgentPoolProfile;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HTTP handler for Azure Kubernetes Service (AKS) management-plane requests.
 *
 * <h2>Routing</h2>
 * <p>All requests use ARM paths:</p>
 * <pre>
 *   GET    subscriptions/{sub}/providers/Microsoft.ContainerService/managedClusters
 *   GET    subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters
 *   GET    subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
 *   PUT    subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
 *   DELETE subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
 *   POST   subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}/listClusterAdminCredential
 *   POST   subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}/listClusterUserCredential
 * </pre>
 *
 * <h2>Mocked mode</h2>
 * <p>When {@code floci-az.services.aks.mocked=true} (default), no k3s container is started.
 * Clusters transition immediately to "Succeeded" with a synthetic kubeconfig pointing at localhost.</p>
 */
@ApplicationScoped
public class AksHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(AksHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final EmulatorConfig config;
    private final AksClusterManager clusterManager;
    private final StorageBackend<String, StoredObject> storage;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aks-readiness-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public AksHandler(EmulatorConfig config,
                      AksClusterManager clusterManager,
                      StorageFactory storageFactory) {
        this.config = config;
        this.clusterManager = clusterManager;
        this.storage = storageFactory.create("aks");
    }

    @PostConstruct
    public void init() {
        if (!config.services().aks().mocked()) {
            startReadinessPoller();
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!config.services().aks().mocked()) {
            scanAll().forEach(cluster -> {
                try {
                    clusterManager.stopCluster(cluster);
                } catch (Exception e) {
                    LOG.warnv("Error stopping AKS cluster {0}: {1}", cluster.getName(), e.getMessage());
                }
            });
        }
    }

    @Override
    public String getServiceType() { return "aks"; }

    @Override
    public boolean canHandle(AzureRequest req) { return "aks".equals(req.serviceType()); }

    @Override
    public Response handle(AzureRequest req) {
        String fullPath = req.resourcePath();
        String method = req.method();

        LOG.debugf("AksHandler: %s %s", method, fullPath);

        // Extract the path segment after "Microsoft.ContainerService/"
        String tail = extractAksPath(fullPath);

        // ── LIST all clusters in subscription ──────────────────────────────
        // subscriptions/{sub}/providers/Microsoft.ContainerService/managedClusters
        if (tail.equalsIgnoreCase("managedClusters") && !fullPath.contains("/resourceGroups/")) {
            return handleListSubscription(fullPath);
        }

        // ── LIST clusters in resource group ────────────────────────────────
        // subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters
        if (tail.equalsIgnoreCase("managedClusters") && "GET".equals(method)) {
            return handleListByResourceGroup(fullPath);
        }

        // ── Credential endpoints ────────────────────────────────────────────
        // managedClusters/{name}/listClusterAdminCredential
        // managedClusters/{name}/listClusterUserCredential
        if (tail.matches("managedClusters/[^/]+/listCluster(?:Admin|User)Credential") && "POST".equals(method)) {
            String clusterName = segment(tail, 1);
            String sub = extractSubscriptionId(fullPath);
            String rg = extractResourceGroup(fullPath);
            return handleListCredentials(sub, rg, clusterName);
        }

        // ── Agent pool list ─────────────────────────────────────────────────
        if (tail.matches("managedClusters/[^/]+/agentPools") && "GET".equals(method)) {
            String clusterName = segment(tail, 1);
            String sub = extractSubscriptionId(fullPath);
            String rg = extractResourceGroup(fullPath);
            return handleListAgentPools(sub, rg, clusterName);
        }

        // ── Agent pool CRUD ─────────────────────────────────────────────────
        if (tail.matches("managedClusters/[^/]+/agentPools/[^/]+")) {
            String clusterName = segment(tail, 1);
            String poolName = segment(tail, 3);
            String sub = extractSubscriptionId(fullPath);
            String rg = extractResourceGroup(fullPath);
            return handleAgentPool(method, sub, rg, clusterName, poolName, req);
        }

        // ── Single cluster CRUD ─────────────────────────────────────────────
        if (tail.matches("managedClusters/[^/]+")) {
            String clusterName = segment(tail, 1);
            String sub = extractSubscriptionId(fullPath);
            String rg = extractResourceGroup(fullPath);
            return switch (method) {
                case "GET"    -> handleGet(sub, rg, clusterName);
                case "PUT"    -> handleCreateOrUpdate(sub, rg, clusterName, req);
                case "DELETE" -> handleDelete(sub, rg, clusterName);
                case "PATCH"  -> handleUpdateTags(sub, rg, clusterName, req);
                default       -> methodNotAllowed();
            };
        }

        return notFound("Unknown AKS path: " + tail);
    }

    // ── CRUD operations ────────────────────────────────────────────────────────

    private Response handleCreateOrUpdate(String sub, String rg, String clusterName, AzureRequest req) {
        try {
            JsonNode body = readBody(req.bodyStream());
            String location = body.path("location").asText("eastus");
            JsonNode props = body.path("properties");
            String k8sVersion = props.path("kubernetesVersion").asText("1.29");
            String dnsPrefix = props.path("dnsPrefix").asText(clusterName + "-dns");
            Map<String, String> tags = parseTags(body.path("tags"));

            List<AgentPoolProfile> pools = new ArrayList<>();
            JsonNode poolsNode = props.path("agentPoolProfiles");
            if (poolsNode.isArray() && !poolsNode.isEmpty()) {
                for (JsonNode p : poolsNode) {
                    AgentPoolProfile pool = new AgentPoolProfile();
                    pool.setName(p.path("name").asText("nodepool1"));
                    pool.setCount(p.path("count").asInt(1));
                    pool.setVmSize(p.path("vmSize").asText("Standard_DS2_v2"));
                    pool.setOsType(p.path("osType").asText("Linux"));
                    pool.setMode(p.path("mode").asText("System"));
                    pool.setProvisioningState("Succeeded");
                    pools.add(pool);
                }
            }
            if (pools.isEmpty()) {
                pools.add(AgentPoolProfile.defaultPool());
            }

            String storageKey = storageKey(sub, rg, clusterName);
            Optional<ManagedCluster> existing = getCluster(storageKey);

            boolean isNew = existing.isEmpty();
            ManagedCluster cluster;
            if (isNew) {
                cluster = new ManagedCluster();
                cluster.setInstanceId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
                cluster.setSubscriptionId(sub);
                cluster.setResourceGroup(rg);
                cluster.setName(clusterName);
                cluster.setCreatedAt(Instant.now());
            } else {
                cluster = existing.get();
            }

            cluster.setLocation(location);
            cluster.setKubernetesVersion(k8sVersion);
            cluster.setDnsPrefix(dnsPrefix);
            cluster.setFqdn(dnsPrefix + ".hcp." + location + ".azmk8s.io");
            cluster.setAgentPoolProfiles(pools);
            cluster.setTags(tags);

            if (config.services().aks().mocked()) {
                cluster.setProvisioningState("Succeeded");
                cluster.setEndpoint("https://localhost:" + config.services().aks().apiServerBasePort());
                cluster.setKubeconfig(mockKubeconfig(cluster));
            } else {
                cluster.setProvisioningState("Creating");
                if (isNew) {
                    try {
                        clusterManager.startCluster(cluster);
                    } catch (Exception e) {
                        LOG.errorf(e, "Failed to start k3s container for AKS cluster %s", clusterName);
                        cluster.setProvisioningState("Failed");
                    }
                }
            }

            putCluster(storageKey, cluster);

            int status = isNew ? 201 : 200;
            return Response.status(status)
                    .entity(toArmResponse(cluster))
                    .type("application/json")
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Error creating/updating AKS cluster %s", clusterName);
            return badRequest("Invalid request: " + e.getMessage());
        }
    }

    private Response handleGet(String sub, String rg, String clusterName) {
        return getCluster(storageKey(sub, rg, clusterName))
                .map(c -> Response.ok(toArmResponse(c)).type("application/json").build())
                .orElseGet(() -> notFound("Managed cluster '" + clusterName + "' not found."));
    }

    private Response handleDelete(String sub, String rg, String clusterName) {
        String key = storageKey(sub, rg, clusterName);
        Optional<ManagedCluster> found = getCluster(key);
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        ManagedCluster cluster = found.get();
        cluster.setProvisioningState("Deleting");
        if (!config.services().aks().mocked()) {
            try {
                clusterManager.stopCluster(cluster);
            } catch (Exception e) {
                LOG.warnv("Error stopping k3s container for cluster {0}: {1}", clusterName, e.getMessage());
            }
        }
        storage.delete(key);
        return Response.status(202).build();
    }

    private Response handleUpdateTags(String sub, String rg, String clusterName, AzureRequest req) {
        String key = storageKey(sub, rg, clusterName);
        Optional<ManagedCluster> found = getCluster(key);
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        try {
            JsonNode body = readBody(req.bodyStream());
            ManagedCluster cluster = found.get();
            cluster.setTags(parseTags(body.path("tags")));
            putCluster(key, cluster);
            return Response.ok(toArmResponse(cluster)).type("application/json").build();
        } catch (Exception e) {
            return badRequest("Invalid request: " + e.getMessage());
        }
    }

    private Response handleListByResourceGroup(String fullPath) {
        String sub = extractSubscriptionId(fullPath);
        String rg = extractResourceGroup(fullPath);
        String prefix = sub + "/" + rg.toLowerCase() + "/";
        List<Map<String, Object>> items = new ArrayList<>();
        scanAll().stream()
                .filter(c -> c.storageKey().toLowerCase().startsWith(prefix))
                .forEach(c -> items.add(toArmResponse(c)));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    private Response handleListSubscription(String fullPath) {
        String sub = extractSubscriptionId(fullPath);
        String prefix = sub + "/";
        List<Map<String, Object>> items = new ArrayList<>();
        scanAll().stream()
                .filter(c -> c.storageKey().startsWith(prefix))
                .forEach(c -> items.add(toArmResponse(c)));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    private Response handleListCredentials(String sub, String rg, String clusterName) {
        Optional<ManagedCluster> found = getCluster(storageKey(sub, rg, clusterName));
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        ManagedCluster cluster = found.get();
        String kubeconfig = cluster.getKubeconfig();
        if (kubeconfig == null || kubeconfig.isBlank()) {
            kubeconfig = mockKubeconfig(cluster);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kubeconfigs", List.of(Map.of(
                "name", "clusterAdmin",
                "value", kubeconfig)));
        return Response.ok(result).type("application/json").build();
    }

    private Response handleListAgentPools(String sub, String rg, String clusterName) {
        Optional<ManagedCluster> found = getCluster(storageKey(sub, rg, clusterName));
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        List<Map<String, Object>> pools = new ArrayList<>();
        List<AgentPoolProfile> profiles = found.get().getAgentPoolProfiles();
        if (profiles != null) {
            for (AgentPoolProfile p : profiles) {
                pools.add(toAgentPoolArmResponse(sub, rg, clusterName, p));
            }
        }
        return Response.ok(Map.of("value", pools)).type("application/json").build();
    }

    private Response handleAgentPool(String method, String sub, String rg,
                                      String clusterName, String poolName, AzureRequest req) {
        Optional<ManagedCluster> found = getCluster(storageKey(sub, rg, clusterName));
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        ManagedCluster cluster = found.get();
        List<AgentPoolProfile> pools = cluster.getAgentPoolProfiles();
        if (pools == null) {
            pools = new ArrayList<>();
            cluster.setAgentPoolProfiles(pools);
        }

        if ("GET".equals(method)) {
            return pools.stream()
                    .filter(p -> poolName.equals(p.getName()))
                    .findFirst()
                    .map(p -> Response.ok(toAgentPoolArmResponse(sub, rg, clusterName, p))
                            .type("application/json").build())
                    .orElseGet(() -> notFound("Agent pool '" + poolName + "' not found."));
        }

        if ("PUT".equals(method)) {
            try {
                JsonNode body = readBody(req.bodyStream());
                JsonNode props = body.path("properties");
                List<AgentPoolProfile> finalPools = pools;
                AgentPoolProfile pool = pools.stream()
                        .filter(p -> poolName.equals(p.getName()))
                        .findFirst()
                        .orElseGet(() -> {
                            AgentPoolProfile np = new AgentPoolProfile();
                            np.setName(poolName);
                            finalPools.add(np);
                            return np;
                        });
                pool.setCount(props.path("count").asInt(pool.getCount() > 0 ? pool.getCount() : 1));
                pool.setVmSize(props.path("vmSize").asText(
                        pool.getVmSize() != null ? pool.getVmSize() : "Standard_DS2_v2"));
                pool.setOsType(props.path("osType").asText(
                        pool.getOsType() != null ? pool.getOsType() : "Linux"));
                pool.setMode(props.path("mode").asText(
                        pool.getMode() != null ? pool.getMode() : "System"));
                pool.setProvisioningState("Succeeded");
                putCluster(storageKey(sub, rg, clusterName), cluster);
                return Response.ok(toAgentPoolArmResponse(sub, rg, clusterName, pool))
                        .type("application/json").build();
            } catch (Exception e) {
                return badRequest("Invalid request: " + e.getMessage());
            }
        }

        if ("DELETE".equals(method)) {
            boolean removed = pools.removeIf(p -> poolName.equals(p.getName()));
            if (!removed) {
                return notFound("Agent pool '" + poolName + "' not found.");
            }
            putCluster(storageKey(sub, rg, clusterName), cluster);
            return Response.status(202).build();
        }

        return methodNotAllowed();
    }

    // ── Readiness poller ───────────────────────────────────────────────────────

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                scanAll().forEach(cluster -> {
                    if ("Creating".equals(cluster.getProvisioningState())) {
                        if (clusterManager.isReady(cluster)) {
                            LOG.infov("AKS cluster {0} is now ready", cluster.getName());
                            clusterManager.finalizeCluster(cluster);
                            cluster.setProvisioningState("Succeeded");
                            putCluster(cluster.storageKey(), cluster);
                        }
                    }
                });
            } catch (Exception e) {
                LOG.error("Error in AKS readiness poller", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    // ── Storage helpers ────────────────────────────────────────────────────────

    private Optional<ManagedCluster> getCluster(String key) {
        return storage.get(key).map(so -> {
            try {
                return MAPPER.readValue(so.data(), ManagedCluster.class);
            } catch (Exception e) {
                LOG.warnv("Failed to deserialize AKS cluster {0}: {1}", key, e.getMessage());
                return null;
            }
        });
    }

    private void putCluster(String key, ManagedCluster cluster) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(cluster);
            storage.put(key, new StoredObject(key, data, Map.of(), Instant.now(), key));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize AKS cluster: " + key, e);
        }
    }

    private List<ManagedCluster> scanAll() {
        List<ManagedCluster> result = new ArrayList<>();
        storage.scan(k -> true).forEach(so -> {
            try {
                ManagedCluster c = MAPPER.readValue(so.data(), ManagedCluster.class);
                if (c != null) { result.add(c); }
            } catch (Exception e) {
                LOG.debugv("Skipping unreadable AKS cluster entry: {0}", e.getMessage());
            }
        });
        return result;
    }

    // ── ARM response builders ──────────────────────────────────────────────────

    private Map<String, Object> toArmResponse(ManagedCluster cluster) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("provisioningState", cluster.getProvisioningState());
        props.put("kubernetesVersion", cluster.getKubernetesVersion());
        props.put("currentKubernetesVersion", cluster.getKubernetesVersion());
        props.put("dnsPrefix", cluster.getDnsPrefix());
        props.put("fqdn", cluster.getFqdn());
        props.put("enableRBAC", true);
        props.put("nodeResourceGroup",
                "MC_" + cluster.getResourceGroup() + "_" + cluster.getName() + "_" + cluster.getLocation());

        if (cluster.getAgentPoolProfiles() != null) {
            List<Map<String, Object>> poolsOut = new ArrayList<>();
            for (AgentPoolProfile p : cluster.getAgentPoolProfiles()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", p.getName());
                pm.put("count", p.getCount());
                pm.put("vmSize", p.getVmSize());
                pm.put("osType", p.getOsType());
                pm.put("mode", p.getMode());
                pm.put("provisioningState", p.getProvisioningState());
                poolsOut.add(pm);
            }
            props.put("agentPoolProfiles", poolsOut);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", cluster.armId());
        out.put("name", cluster.getName());
        out.put("type", "Microsoft.ContainerService/managedClusters");
        out.put("location", cluster.getLocation());
        if (cluster.getTags() != null && !cluster.getTags().isEmpty()) {
            out.put("tags", cluster.getTags());
        }
        out.put("properties", props);
        return out;
    }

    private Map<String, Object> toAgentPoolArmResponse(String sub, String rg,
                                                         String clusterName, AgentPoolProfile pool) {
        String id = String.format(
                "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.ContainerService" +
                "/managedClusters/%s/agentPools/%s",
                sub, rg, clusterName, pool.getName());
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("count", pool.getCount());
        props.put("vmSize", pool.getVmSize());
        props.put("osType", pool.getOsType());
        props.put("mode", pool.getMode());
        props.put("provisioningState", pool.getProvisioningState());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", pool.getName());
        out.put("type", "Microsoft.ContainerService/managedClusters/agentPools");
        out.put("properties", props);
        return out;
    }

    // ── Mock kubeconfig ────────────────────────────────────────────────────────

    private static String mockKubeconfig(ManagedCluster cluster) {
        String endpoint = cluster.getEndpoint() != null
                ? cluster.getEndpoint()
                : "https://localhost:6443";
        String yaml = String.format("""
                apiVersion: v1
                clusters:
                - cluster:
                    server: %s
                    insecure-skip-tls-verify: true
                  name: %s
                contexts:
                - context:
                    cluster: %s
                    user: clusterAdmin_%s
                  name: %s
                current-context: %s
                kind: Config
                preferences: {}
                users:
                - name: clusterAdmin_%s
                  user:
                    token: floci-az-mock-token
                """,
                endpoint, cluster.getName(), cluster.getName(),
                cluster.getName(), cluster.getName(), cluster.getName(),
                cluster.getName());
        return Base64.getEncoder().encodeToString(yaml.getBytes(StandardCharsets.UTF_8));
    }

    // ── Path parsing helpers ───────────────────────────────────────────────────

    private static String extractAksPath(String fullPath) {
        if (fullPath == null) { return ""; }
        int idx = fullPath.indexOf("/providers/Microsoft.ContainerService/");
        if (idx >= 0) {
            return fullPath.substring(idx + "/providers/Microsoft.ContainerService/".length());
        }
        return fullPath;
    }

    private static String extractSubscriptionId(String fullPath) {
        if (fullPath == null) { return "default"; }
        String[] parts = fullPath.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("subscriptions".equalsIgnoreCase(parts[i])) { return parts[i + 1]; }
        }
        return "default";
    }

    private static String extractResourceGroup(String fullPath) {
        if (fullPath == null) { return "default"; }
        String[] parts = fullPath.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("resourcegroups".equalsIgnoreCase(parts[i])) { return parts[i + 1]; }
        }
        return "default";
    }

    private static String segment(String path, int index) {
        String[] parts = path.split("/");
        return index < parts.length ? parts[index] : "";
    }

    private static String storageKey(String sub, String rg, String clusterName) {
        return sub + "/" + rg + "/" + clusterName;
    }

    private static Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }

    private JsonNode readBody(java.io.InputStream stream) {
        try {
            if (stream == null || stream.available() == 0) { return MAPPER.createObjectNode(); }
            return MAPPER.readTree(stream);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    // ── Standard error responses ───────────────────────────────────────────────

    private static Response notFound(String message) {
        return Response.status(404).entity(Map.of(
                "error", Map.of("code", "ResourceNotFound", "message", message)))
                .type("application/json").build();
    }

    private static Response badRequest(String message) {
        return Response.status(400).entity(Map.of(
                "error", Map.of("code", "InvalidRequest", "message", message)))
                .type("application/json").build();
    }

    private static Response methodNotAllowed() {
        return Response.status(405).entity(Map.of("error", "Method not allowed"))
                .type("application/json").build();
    }

    /** Wipes all AKS data — used by {@code POST /_admin/reset}. */
    public void clearAll() {
        storage.clear();
    }
}
