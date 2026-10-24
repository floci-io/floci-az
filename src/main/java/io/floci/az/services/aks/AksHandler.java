package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.ResourceIndexContributor;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.aks.AksModels.AgentPoolProfile;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
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
 *   GET    subscriptions/{sub}/providers/Microsoft.ContainerService/locations/{loc}/operations/{opId}        (LRO status)
 *   GET    subscriptions/{sub}/providers/Microsoft.ContainerService/locations/{loc}/operationresults/{opId}  (LRO result)
 *   POST   subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}/listClusterAdminCredential
 *   POST   subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}/listClusterUserCredential
 * </pre>
 *
 * <h2>Mocked mode</h2>
 * <p>When {@code floci-az.services.aks.mocked=true} (default), no k3s container is started.
 * Clusters transition immediately to "Succeeded" with a synthetic kubeconfig pointing at localhost.</p>
 */
@ApplicationScoped
public class AksHandler implements AzureServiceHandler, Resettable, ResourceIndexContributor {

    private static final Logger LOG = Logger.getLogger(AksHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String TYPE = "Microsoft.ContainerService/managedClusters";
    private static final String PROVIDER_PREFIX = "/providers/Microsoft.ContainerService/";

    private final EmulatorConfig config;
    private final AksClusterManager clusterManager;
    /**
     * Every cluster, keyed by its canonical id. Before the store, clusters were kept under raw-case
     * {@code sub/rg/name}; those entries are read as they are and move to their canonical key on their next write.
     */
    private final ArmResourceStore<ManagedCluster> store;
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
        this.store = ArmResourceStore.create(storageFactory, "aks", ManagedCluster.class, ManagedCluster::armId,
                key -> true);
    }

    @PostConstruct
    public void init() {
        dropShadowedClusters();
        if (!config.services().aks().mocked()) {
            startReadinessPoller();
        }
    }

    /**
     * Old raw-case keys could hold one cluster under several casings, each with a k3s container of its own (named
     * from the record's {@code instanceId}). The store serves one of them; the others are removed here, with their
     * containers, before anything could drop them and leave the containers running.
     */
    void dropShadowedClusters() {
        for (ArmResourceStore.Stored<ManagedCluster> shadowed : store.removeShadowedLegacy()) {
            ManagedCluster cluster = shadowed.value();
            LOG.infov("AKS cluster {0}: dropping the duplicate stored under {1}/{2}", cluster.getName(),
                    cluster.getSubscriptionId(), cluster.getResourceGroup());
            if (!config.services().aks().mocked()) {
                try {
                    clusterManager.removeCluster(cluster);
                } catch (Exception e) {
                    LOG.warnv("Could not remove the k3s container of duplicate AKS cluster {0}: {1}. Remove container"
                            + " {2} (id {3}) and volume {2} by hand", cluster.getName(), e.getMessage(),
                            clusterManager.containerName(cluster), cluster.getContainerId());
                }
            }
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
    public boolean enabled(String serviceType) {
        return config.services().aks().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .provider("Microsoft.ContainerService")
                .build();
    }

    @Override
    public Response handle(AzureRequest req) {
        String fullPath = req.resourcePath();
        String method = req.method();

        LOG.debugf("AksHandler: %s %s", method, fullPath);

        // Extract the path segment after "Microsoft.ContainerService/"
        String tail = extractAksPath(fullPath);

        // ── LRO status monitors handed out by DELETE ────────────────────────
        // locations/{loc}/operations/{opId} and locations/{loc}/operationresults/{opId}
        if ("GET".equals(method) && tail.matches("(?i)locations/[^/]+/operations/[^/]+")) {
            return operationStatusResponse(segment(tail, 3));
        }
        if ("GET".equals(method) && tail.matches("(?i)locations/[^/]+/operationresults/[^/]+")) {
            return Response.noContent().build();
        }

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
                case "DELETE" -> handleDelete(req, sub, rg, clusterName);
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
            // The gate checks an agentPools/{name} URL; pools embedded in the cluster body get the same rule here.
            if (config.strictNames()) {
                for (AgentPoolProfile pool : pools) {
                    if (!AksArmSurface.AGENT_POOL_NAME.accepts(pool.getName())) {
                        return ArmErrors.error(400, AksArmSurface.AGENT_POOL_NAME.code(),
                                AksArmSurface.AGENT_POOL_NAME.message(pool.getName()));
                    }
                }
            }
            if (pools.isEmpty()) {
                pools.add(AgentPoolProfile.defaultPool());
            }

            Optional<ManagedCluster> existing = getCluster(clusterId(sub, rg, clusterName));

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

            store.put(cluster);

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
        return getCluster(clusterId(sub, rg, clusterName))
                .map(c -> Response.ok(toArmResponse(c)).type("application/json").build())
                .orElseGet(() -> notFound("Managed cluster '" + clusterName + "' not found."));
    }

    /**
     * ManagedClusters_Delete is a long-running operation (final-state-via azure-async-operation)
     * declaring only 202, with Azure-AsyncOperation, Location and Retry-After, and 204 when the
     * cluster does not exist. The cluster is removed before answering, so the status monitor
     * already reports Succeeded on the first poll.
     */
    private Response handleDelete(AzureRequest req, String sub, String rg, String clusterName) {
        ArmResourceId id = clusterId(sub, rg, clusterName);
        Optional<ManagedCluster> found = getCluster(id);
        if (found.isEmpty()) {
            return Response.noContent().build();
        }
        ManagedCluster cluster = found.get();
        cluster.setProvisioningState("Deleting");
        if (!config.services().aks().mocked()) {
            try {
                clusterManager.removeCluster(cluster);
            } catch (Exception e) {
                LOG.warnv("Error removing k3s container for cluster {0}: {1}", clusterName, e.getMessage());
            }
        }
        store.delete(id);
        return acceptedDelete(req, sub, cluster.getLocation());
    }

    private Response acceptedDelete(AzureRequest req, String sub, String location) {
        String loc = (location == null || location.isBlank()) ? defaultLocation(req) : location;
        String operationId = UUID.randomUUID().toString();
        // Built from the caller's own scheme and host, like the ACI monitors: a client that reached
        // us over TLS refuses to send credentials to a downgraded http:// poll URL.
        String locationBase = RequestUrls.resolveBaseUrl(req, config) + "/subscriptions/" + sub
                + PROVIDER_PREFIX + "locations/" + loc;
        String apiVersion = req.queryParams() == null ? null : req.queryParams().get("api-version");
        String query = apiVersion == null || apiVersion.isBlank() ? "" : "?api-version=" + apiVersion;
        return Response.status(202)
                .header("Azure-AsyncOperation", locationBase + "/operations/" + operationId + query)
                .header("Location", locationBase + "/operationresults/" + operationId + query)
                .header("Retry-After", "1")
                .build();
    }

    /** The default location of the cloud the request resolved to, for a cluster stored without one. */
    private static String defaultLocation(AzureRequest req) {
        CloudProfile cloud = req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD)
            : req.scope().cloud();
        return cloud.effectiveDefaultLocation();
    }

    private static Response operationStatusResponse(String operationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", operationId);
        body.put("status", "Succeeded");
        return Response.ok(body).type("application/json").build();
    }

    private Response handleUpdateTags(String sub, String rg, String clusterName, AzureRequest req) {
        Optional<ManagedCluster> found = getCluster(clusterId(sub, rg, clusterName));
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        try {
            JsonNode body = readBody(req.bodyStream());
            ManagedCluster cluster = found.get();
            cluster.setTags(parseTags(body.path("tags")));
            store.put(cluster);
            return Response.ok(toArmResponse(cluster)).type("application/json").build();
        } catch (Exception e) {
            return badRequest("Invalid request: " + e.getMessage());
        }
    }

    private Response handleListByResourceGroup(String fullPath) {
        return list(new ArmScope(extractSubscriptionId(fullPath), extractResourceGroup(fullPath)));
    }

    private Response handleListSubscription(String fullPath) {
        return list(new ArmScope(extractSubscriptionId(fullPath), null));
    }

    private Response list(ArmScope scope) {
        List<Map<String, Object>> items = new ArrayList<>();
        store.list(scope).forEach(stored -> items.add(toArmResponse(stored.value())));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    private Response handleListCredentials(String sub, String rg, String clusterName) {
        Optional<ManagedCluster> found = getCluster(clusterId(sub, rg, clusterName));
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        ManagedCluster cluster = found.get();
        String kubeconfig = cluster.getKubeconfig();
        if (kubeconfig == null || kubeconfig.isBlank()) {
            if (!config.services().aks().mocked()) {
                return credentialsNotAvailable(cluster);
            }
            kubeconfig = mockKubeconfig(cluster);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kubeconfigs", List.of(Map.of(
                "name", "clusterAdmin",
                "value", kubeconfig)));
        return Response.ok(result).type("application/json").build();
    }

    /**
     * Real mode only: the k3s kubeconfig has not been extracted, because the cluster is still
     * {@code Creating}, it {@code Failed}, or extraction failed. Answering with a synthetic
     * kubeconfig would hand the caller a server and token that do not work, so report the
     * cluster state instead, with the code AKS uses for operations its current state forbids.
     */
    private static Response credentialsNotAvailable(ManagedCluster cluster) {
        return ArmErrors.error(409, "OperationNotAllowed",
                "Managed cluster '" + cluster.getName() + "' is in Provisioning State("
                        + cluster.getProvisioningState() + "), listing credentials cannot be performed"
                        + " because the cluster has no kubeconfig yet.");
    }

    private Response handleListAgentPools(String sub, String rg, String clusterName) {
        Optional<ManagedCluster> found = getCluster(clusterId(sub, rg, clusterName));
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
        Optional<ManagedCluster> found = getCluster(clusterId(sub, rg, clusterName));
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
                store.put(cluster);
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
            store.put(cluster);
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
                            // Only over the same instance: a DELETE while the cluster started must not be undone.
                            store.update(clusterId(cluster), current ->
                                    cluster.getInstanceId().equals(current.getInstanceId()) ? cluster : current);
                        }
                    }
                });
            } catch (Exception e) {
                LOG.error("Error in AKS readiness poller", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    // ── Storage helpers ────────────────────────────────────────────────────────

    private Optional<ManagedCluster> getCluster(ArmResourceId id) {
        return store.get(id).map(ArmResourceStore.Stored::value);
    }

    private List<ManagedCluster> scanAll() {
        return store.all().stream().map(ArmResourceStore.Stored::value).toList();
    }

    private static ArmResourceId clusterId(String sub, String rg, String clusterName) {
        return ArmResourceId.parse(String.format(
                "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.ContainerService/managedClusters/%s",
                sub, rg, clusterName)).orElseThrow();
    }

    private static ArmResourceId clusterId(ManagedCluster cluster) {
        return ArmResourceId.parse(cluster.armId()).orElseThrow();
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
        out.put("type", TYPE);
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
        int idx = fullPath.indexOf(PROVIDER_PREFIX);
        if (idx >= 0) {
            return fullPath.substring(idx + PROVIDER_PREFIX.length());
        }
        return fullPath;
    }

    private static String extractSubscriptionId(String fullPath) {
        return ArmPaths.segmentAfter(fullPath, "subscriptions", "default");
    }

    private static String extractResourceGroup(String fullPath) {
        return ArmPaths.resourceGroup(fullPath, "default");
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
            if (stream == null || stream.available() == 0) { return MAPPER.createObjectNode(); }
            return MAPPER.readTree(stream);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    // ── Standard error responses ───────────────────────────────────────────────

    private static Response notFound(String message) {
        return ArmErrors.notFound(message);
    }

    private static Response badRequest(String message) {
        return ArmErrors.error(400, "InvalidRequest", message);
    }

    private static Response methodNotAllowed() {
        return Response.status(405).entity(Map.of("error", "Method not allowed"))
                .type("application/json").build();
    }

    /** Wipes all AKS data — used by {@code POST /_admin/reset}. */
    public void clear() {
        store.clear();
    }

    // ── ResourceIndexContributor ────────────────────────────────────────────────

    @Override
    public boolean indexEnabled() {
        return config.services().aks().enabled();
    }

    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return indexEntries(new ArmScope(sub, rg));
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return indexEntries(new ArmScope(sub, null));
    }

    private List<Map<String, Object>> indexEntries(ArmScope scope) {
        return store.list(scope).stream()
                .map(ArmResourceStore.Stored::value)
                .map(cluster -> ArmResources.indexEntry(cluster.armId(), cluster.getName(), TYPE,
                        cluster.getLocation(), cluster.getTags()))
                .toList();
    }
}
