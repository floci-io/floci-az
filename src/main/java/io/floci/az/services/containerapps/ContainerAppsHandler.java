package io.floci.az.services.containerapps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ResourceIndexContributor;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.containerapps.ContainerAppsModels.ContainerApp;
import io.floci.az.services.containerapps.ContainerAppsModels.Job;
import io.floci.az.services.containerapps.ContainerAppsModels.ManagedEnvironment;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * HTTP handler for Azure Container Apps ({@code Microsoft.App}) management-plane requests,
 * covering all three resource roots under the namespace: {@code managedEnvironments},
 * {@code containerApps}, and {@code jobs}.
 *
 * <h2>Routing</h2>
 * <pre>
 *   GET    subscriptions/{sub}/providers/Microsoft.App/managedEnvironments                    (list all)
 *   GET    subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments (list in rg)
 *   PUT    .../managedEnvironments/{name}         create/update (sync, full body)
 *   GET    .../managedEnvironments/{name}
 *   PATCH  .../managedEnvironments/{name}         update (the provider's internal PATCH workaround client)
 *   DELETE .../managedEnvironments/{name}
 *
 *   GET    .../containerApps                      (list, sub- and rg-scoped, as above)
 *   PUT    .../containerApps/{name}                create/update (sync, full body — no PATCH, per spec)
 *   GET    .../containerApps/{name}
 *   DELETE .../containerApps/{name}
 *   POST   .../containerApps/{name}/listSecrets
 *
 *   GET    .../jobs                                (list, sub- and rg-scoped, as above)
 *   PUT    .../jobs/{name}                          create/update (sync, full body)
 *   GET    .../jobs/{name}
 *   DELETE .../jobs/{name}
 *   POST   .../jobs/{name}/listSecrets
 * </pre>
 *
 * <h2>LRO shapes</h2>
 * <p>All three resource types' real Azure PUTs go through {@code go-azure-sdk}'s generic
 * {@code PollerFromResponse}. The emulator returns the full body with {@code provisioningState}
 * already {@code Succeeded} and no {@code Azure-AsyncOperation}/{@code Location} header — the
 * poller sees a synchronously-complete operation and returns without a second GET, the same shape
 * ACI and VM use for their PUTs. DELETE is likewise a synchronous {@code 204} (idempotent when
 * absent): a {@code 202} with no operation endpoint makes the azurerm provider's DeleteThenPoll
 * poll forever, per the identical rationale documented on {@code AciHandler}/{@code VmHandler}.
 * The environment's {@code PATCH} (the provider's own internal Update workaround) is likewise
 * synchronous, answering {@code 200} — the spec accepts {@code 200} or {@code 202} and the
 * synchronous shape needs no operation-status stub.</p>
 *
 * <h2>Mocked mode</h2>
 * <p>When {@code floci-az.services.containerapps.mocked=true} (default), no Docker
 * container/runtime is started. Environments, apps and jobs transition immediately to
 * {@code provisioningState=Succeeded} with synthetic (but per-resource stable — see
 * {@link #synthLabel}) domains, IPs, and revision names. This is the only mode implemented today.</p>
 *
 * <h2>Log Analytics dependency</h2>
 * <p>The environment resource's real {@code Create()} also calls three
 * {@code Microsoft.OperationalInsights} endpoints to seed the Log Analytics workspace's
 * {@code customerId}/{@code sharedKey} (workspace GET, {@code sharedKeys} POST, and a
 * subscription-wide workspace list used on every environment {@code Read()} to reverse-resolve
 * {@code log_analytics_workspace_id}). That provider namespace belongs to {@link
 * io.floci.az.services.monitor.MonitorHandler}, which implements all three, so a real
 * {@code azurerm_container_app_environment} apply with {@code log_analytics_workspace_id} set
 * works end to end.</p>
 */
@ApplicationScoped
public class ContainerAppsHandler implements AzureServiceHandler, Resettable, ResourceIndexContributor {

    private static final Logger LOG = Logger.getLogger(ContainerAppsHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String MARKER = "/providers/Microsoft.App/";
    private static final String API_VERSION = "2025-07-01";

    private static final String ENV_TYPE = "Microsoft.App/managedEnvironments";
    private static final String APP_TYPE = "Microsoft.App/containerApps";
    private static final String JOB_TYPE = "Microsoft.App/jobs";

    /** Server-side defaults matching real Azure Container Apps' minimums, applied when omitted. */
    private static final double DEFAULT_CPU = 0.25;
    private static final String DEFAULT_MEMORY = "0.5Gi";
    private static final int DEFAULT_MIN_REPLICAS = 0;
    private static final int DEFAULT_MAX_REPLICAS = 10;
    private static final int DEFAULT_REPLICA_TIMEOUT_SECONDS = 1800;

    private final EmulatorConfig config;
    private final StorageBackend<String, StoredObject> envStorage;
    private final StorageBackend<String, StoredObject> appStorage;
    private final StorageBackend<String, StoredObject> jobStorage;

    @Inject
    public ContainerAppsHandler(EmulatorConfig config, StorageFactory storageFactory) {
        this.config = config;
        this.envStorage = storageFactory.create("containerapps-env");
        this.appStorage = storageFactory.create("containerapps-app");
        this.jobStorage = storageFactory.create("containerapps-job");
    }

    void onStart(@Observes StartupEvent event) {
        if (config.services().containerapps().enabled() && !config.services().containerapps().mocked()) {
            LOG.warn("ContainerApps: runtime-backed mode (floci-az.services.containerapps.mocked=false) "
                    + "is not available yet; environments/apps/jobs are emulated as mocked ARM state");
        }
    }

    @Override
    public String getServiceType() { return "containerapps"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().containerapps().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .provider("Microsoft.App")
                .build();
    }

    @Override
    public boolean canHandle(AzureRequest req) { return "containerapps".equals(req.serviceType()); }

    @Override
    public Response handle(AzureRequest req) {
        String fullPath = req.resourcePath();
        String method = req.method().toUpperCase();
        String tail = extractPath(fullPath);

        LOG.debugf("ContainerAppsHandler: %s %s (tail=%s)", method, fullPath, tail);

        // ── LRO operation status (returns terminal Succeeded immediately) ──────
        if (tail.matches("locations/[^/]+/operation(?:s|Results)/[^/?]+.*")) {
            return Response.ok(Map.of("status", "Succeeded")).type(MediaType.APPLICATION_JSON).build();
        }

        String sub = extractSubscriptionId(fullPath);
        String rg = extractResourceGroup(fullPath);

        if (tail.matches("managedEnvironments(?:/.*)?(?:[?].*)?")) {
            return handleManagedEnvironments(sub, rg, fullPath, tail, method, req);
        }
        if (tail.matches("containerApps(?:/.*)?(?:[?].*)?")) {
            return handleContainerApps(sub, rg, fullPath, tail, method, req);
        }
        if (tail.matches("jobs(?:/.*)?(?:[?].*)?")) {
            return handleJobs(sub, rg, fullPath, tail, method, req);
        }

        return ArmErrors.notFound("Unsupported Microsoft.App path: " + tail);
    }

    // ── managedEnvironments ───────────────────────────────────────────────────

    private Response handleManagedEnvironments(String sub, String rg, String fullPath, String tail,
                                                String method, AzureRequest req) {
        if (tail.matches("managedEnvironments(?:[?].*)?") && !fullPath.contains("/resourceGroups/")) {
            return handleEnvList(sub, null);
        }
        if (tail.matches("managedEnvironments(?:[?].*)?") && "GET".equals(method)) {
            return handleEnvList(sub, rg);
        }
        if (tail.matches("managedEnvironments/[^/]+(?:[?].*)?")) {
            String name = segment(tail, 1);
            return switch (method) {
                case "GET"    -> handleEnvGet(sub, rg, name);
                case "PUT"    -> handleEnvCreateOrUpdate(sub, rg, name, req);
                case "PATCH"  -> handleEnvUpdate(sub, rg, name, req);
                case "DELETE" -> handleEnvDelete(sub, rg, name);
                default       -> ArmErrors.error(405, "MethodNotAllowed", "Method not allowed");
            };
        }
        return ArmErrors.notFound("Unsupported Microsoft.App/managedEnvironments path: " + tail);
    }

    private Response handleEnvCreateOrUpdate(String sub, String rg, String name, AzureRequest req) {
        try {
            JsonNode body = readBody(req.bodyStream());
            Map<String, Object> properties = objectToMap(body.path("properties"));
            normalizeEnvironmentProperties(properties);

            String key = storageKey(sub, rg, name);
            Optional<ManagedEnvironment> existing = getEnv(key);
            boolean isNew = existing.isEmpty();

            ManagedEnvironment env = existing.orElseGet(ManagedEnvironment::new);
            if (isNew) {
                env.setSubscriptionId(sub);
                env.setResourceGroup(rg);
                env.setName(name);
                env.setTimeCreated(Instant.now());
            }
            env.setLocation(body.path("location").asText(env.getLocation() == null ? "eastus" : env.getLocation()));
            Map<String, String> tags = parseTags(body.path("tags"));
            env.setTags(tags.isEmpty() ? null : tags);
            env.setIdentity(body.has("identity") ? objectToMap(body.path("identity")) : defaultIdentity());
            env.setProperties(properties);
            env.setProvisioningState("Succeeded");

            putEnv(key, env);
            return Response.status(isNew ? 201 : 200)
                    .entity(toEnvArmResponse(env))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Error creating/updating managed environment %s", name);
            return ArmErrors.error(400, "InvalidRequestContent", "Invalid request: " + e.getMessage());
        }
    }

    /**
     * The provider's internal "Update" workaround client PUTs via PATCH. Real Azure treats this as
     * a partial update; the emulator merges the submitted top-level fields onto the existing
     * resource (never dropping fields the client didn't mention) and re-normalizes.
     */
    private Response handleEnvUpdate(String sub, String rg, String name, AzureRequest req) {
        String key = storageKey(sub, rg, name);
        Optional<ManagedEnvironment> found = getEnv(key);
        if (found.isEmpty()) {
            return envNotFound(rg, name);
        }
        try {
            JsonNode body = readBody(req.bodyStream());
            ManagedEnvironment env = found.get();
            if (body.has("location")) {
                env.setLocation(body.path("location").asText(env.getLocation()));
            }
            if (body.has("tags")) {
                Map<String, String> tags = parseTags(body.path("tags"));
                env.setTags(tags.isEmpty() ? null : tags);
            }
            if (body.has("identity")) {
                env.setIdentity(objectToMap(body.path("identity")));
            }
            if (body.has("properties")) {
                Map<String, Object> properties = objectToMap(body.path("properties"));
                normalizeEnvironmentProperties(properties);
                env.setProperties(properties);
            }
            env.setProvisioningState("Succeeded");
            putEnv(key, env);
            return Response.ok(toEnvArmResponse(env)).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            return ArmErrors.error(400, "InvalidRequestContent", "Invalid request: " + e.getMessage());
        }
    }

    private Response handleEnvGet(String sub, String rg, String name) {
        return getEnv(storageKey(sub, rg, name))
                .map(env -> Response.ok(toEnvArmResponse(env)).type(MediaType.APPLICATION_JSON).build())
                .orElseGet(() -> envNotFound(rg, name));
    }

    private Response handleEnvDelete(String sub, String rg, String name) {
        // Synchronous 204 in both cases (existing or not), matching ACI/VM's DELETE discipline: a
        // 202 with no operation endpoint makes azurerm's DeleteThenPoll poll forever.
        String key = storageKey(sub, rg, name);
        envStorage.delete(key);
        return Response.status(204).build();
    }

    private Response handleEnvList(String sub, String rg) {
        String prefix = rg == null ? (sub + "/").toLowerCase() : (sub + "/" + rg + "/").toLowerCase();
        List<Map<String, Object>> items = new ArrayList<>();
        scanEnvs().stream()
                .filter(env -> env.storageKey().toLowerCase().startsWith(prefix))
                .forEach(env -> items.add(toEnvArmResponse(env)));
        return Response.ok(Map.of("value", items)).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Applies server-side defaults in place. {@code appLogsConfiguration.destination} is forced to
     * {@code "log-analytics"} whenever a Log Analytics customerId is present, exactly as the real
     * Create() does server-side even though the client never sends it explicitly (pinned-tag
     * azurerm 4.79.0's CustomizeDiff only errors on an explicit conflicting config value, so ground
     * truth's unset logs_destination reaches here and must read back as "log-analytics" or the
     * Optional+Computed field drifts on every plan).
     */
    @SuppressWarnings("unchecked")
    private void normalizeEnvironmentProperties(Map<String, Object> properties) {
        Map<String, Object> appLogs = properties.get("appLogsConfiguration") instanceof Map<?, ?> al
                ? (Map<String, Object>) al : new LinkedHashMap<>();
        Object logAnalyticsRaw = appLogs.get("logAnalyticsConfiguration");
        if (logAnalyticsRaw instanceof Map<?, ?> la && asString(la.get("customerId")) != null) {
            appLogs.put("destination", "log-analytics");
        }
        properties.put("appLogsConfiguration", appLogs);

        if (properties.get("vnetConfiguration") instanceof Map<?, ?> v) {
            Map<String, Object> vnet = (Map<String, Object>) v;
            vnet.putIfAbsent("internal", false);
            properties.put("vnetConfiguration", vnet);
        }
        properties.putIfAbsent("zoneRedundant", false);
    }

    private Map<String, Object> toEnvArmResponse(ManagedEnvironment env) {
        Map<String, Object> props = deepCopy(env.getProperties());
        String seed = env.storageKey();

        if (props.get("appLogsConfiguration") instanceof Map<?, ?> al) {
            @SuppressWarnings("unchecked")
            Map<String, Object> appLogs = (Map<String, Object>) al;
            if (appLogs.get("logAnalyticsConfiguration") instanceof Map<?, ?> la) {
                @SuppressWarnings("unchecked")
                Map<String, Object> logAnalytics = (Map<String, Object>) la;
                // Secret hygiene: the shared key is accepted on PUT to seed the environment but is
                // never echoed back on any read path.
                logAnalytics.remove("sharedKey");
                appLogs.put("logAnalyticsConfiguration", logAnalytics);
            }
            props.put("appLogsConfiguration", appLogs);
        }

        if (props.get("vnetConfiguration") instanceof Map<?, ?> v) {
            @SuppressWarnings("unchecked")
            Map<String, Object> vnet = (Map<String, Object>) v;
            String octet = synthOctet(seed + ":vnet");
            vnet.put("dockerBridgeCidr", "10." + octet + ".0.1/16");
            vnet.put("platformReservedCidr", "10." + octet + ".0.0/16");
            vnet.put("platformReservedDnsIP", "10." + octet + ".0.2");
            props.put("vnetConfiguration", vnet);
        }

        props.put("provisioningState", "Succeeded");
        props.put("customDomainConfiguration", Map.of("customDomainVerificationId", synthHex(seed + ":cdvid", 64)));
        props.put("defaultDomain", environmentDefaultDomain(seed, env.getLocation()));
        props.put("staticIp", synthIpv4(seed + ":staticip"));
        props.put("publicNetworkAccess", "Enabled");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", env.armId());
        out.put("name", env.getName());
        out.put("type", ENV_TYPE);
        out.put("location", env.getLocation());
        out.put("identity", identityForGet(env.getIdentity(), seed));
        if (env.getTags() != null && !env.getTags().isEmpty()) {
            out.put("tags", env.getTags());
        }
        out.put("properties", props);
        return out;
    }

    private Response envNotFound(String rg, String name) {
        return ArmErrors.notFound("The Resource '" + ENV_TYPE + "/" + name
                + "' under resource group '" + rg + "' was not found.");
    }

    // ── containerApps ─────────────────────────────────────────────────────────

    private Response handleContainerApps(String sub, String rg, String fullPath, String tail,
                                          String method, AzureRequest req) {
        if (tail.matches("containerApps(?:[?].*)?") && !fullPath.contains("/resourceGroups/")) {
            return handleAppList(sub, null);
        }
        if (tail.matches("containerApps(?:[?].*)?") && "GET".equals(method)) {
            return handleAppList(sub, rg);
        }
        if (tail.matches("containerApps/[^/]+/listSecrets(?:[?].*)?") && "POST".equals(method)) {
            return handleAppListSecrets(sub, rg, segment(tail, 1));
        }
        if (tail.matches("containerApps/[^/]+(?:[?].*)?")) {
            String name = segment(tail, 1);
            return switch (method) {
                case "GET"    -> handleAppGet(sub, rg, name);
                case "PUT"    -> handleAppCreateOrUpdate(sub, rg, name, req);
                case "DELETE" -> handleAppDelete(sub, rg, name);
                default       -> ArmErrors.error(405, "MethodNotAllowed", "Method not allowed");
            };
        }
        return ArmErrors.notFound("Unsupported Microsoft.App/containerApps path: " + tail);
    }

    private Response handleAppCreateOrUpdate(String sub, String rg, String name, AzureRequest req) {
        try {
            JsonNode body = readBody(req.bodyStream());
            Map<String, Object> properties = objectToMap(body.path("properties"));
            @SuppressWarnings("unchecked")
            Map<String, Object> template = properties.get("template") instanceof Map<?, ?> t
                    ? (Map<String, Object>) t : new LinkedHashMap<>();
            properties.put("template", template);

            Response invalid = validateContainers(listOfMaps(template.get("containers")));
            if (invalid != null) {
                return invalid;
            }
            normalizeAppProperties(properties);

            String key = storageKey(sub, rg, name);
            Optional<ContainerApp> existing = getApp(key);
            boolean isNew = existing.isEmpty();

            ContainerApp app = existing.orElseGet(ContainerApp::new);
            if (isNew) {
                app.setSubscriptionId(sub);
                app.setResourceGroup(rg);
                app.setName(name);
                app.setTimeCreated(Instant.now());
            }
            app.setLocation(body.path("location").asText(app.getLocation() == null ? "eastus" : app.getLocation()));
            Map<String, String> tags = parseTags(body.path("tags"));
            app.setTags(tags.isEmpty() ? null : tags);
            app.setIdentity(body.has("identity") ? objectToMap(body.path("identity")) : defaultIdentity());
            app.setProperties(properties);

            // template.revisionSuffix arrives "" when the client leaves revision naming to the
            // server (the common case) — echo a non-blank client value verbatim (§8: never
            // fabricate over what was sent), otherwise keep whatever this resource already had, or
            // synthesize once on first create. Either way it's stable across subsequent GETs.
            String clientSuffix = asString(template.get("revisionSuffix"));
            if (clientSuffix != null && !clientSuffix.isBlank()) {
                app.setRevisionSuffix(clientSuffix);
            } else if (app.getRevisionSuffix() == null) {
                app.setRevisionSuffix(synthLabel(key + ":revision", 10));
            }

            app.setProvisioningState("Succeeded");
            putApp(key, app);
            return Response.status(isNew ? 201 : 200)
                    .entity(toAppArmResponse(app))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Error creating/updating container app %s", name);
            return ArmErrors.error(400, "InvalidRequestContent", "Invalid request: " + e.getMessage());
        }
    }

    private Response handleAppGet(String sub, String rg, String name) {
        return getApp(storageKey(sub, rg, name))
                .map(app -> Response.ok(toAppArmResponse(app)).type(MediaType.APPLICATION_JSON).build())
                .orElseGet(() -> appNotFound(rg, name));
    }

    private Response handleAppDelete(String sub, String rg, String name) {
        String key = storageKey(sub, rg, name);
        appStorage.delete(key);
        return Response.status(204).build();
    }

    private Response handleAppList(String sub, String rg) {
        String prefix = rg == null ? (sub + "/").toLowerCase() : (sub + "/" + rg + "/").toLowerCase();
        List<Map<String, Object>> items = new ArrayList<>();
        scanApps().stream()
                .filter(app -> app.storageKey().toLowerCase().startsWith(prefix))
                .forEach(app -> items.add(toAppArmResponse(app)));
        return Response.ok(Map.of("value", items)).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * {@code POST .../listSecrets} — the app's Read() calls this on every GET and hard-fails the
     * whole read (and therefore apply/plan) on anything but 200, so unlike GET this must always
     * succeed for a resource that exists. Echoes each stored secret's {@code keyVaultUrl}/
     * {@code identity} byte-identical (never re-cased or rewritten — a changed value is a
     * permanent plan diff), or its raw {@code value} for a non-KV secret.
     */
    private Response handleAppListSecrets(String sub, String rg, String name) {
        Optional<ContainerApp> found = getApp(storageKey(sub, rg, name));
        if (found.isEmpty()) {
            return appNotFound(rg, name);
        }
        return Response.ok(listSecretsResponse(secretsOf(found.get().getProperties())))
                .type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Applies server-side defaults and canonical enum casing in place, mirroring
     * {@code AciHandler.normalizeProperties}'s discipline so a second {@code terraform plan}
     * re-reads exactly what it wrote.
     */
    @SuppressWarnings("unchecked")
    private void normalizeAppProperties(Map<String, Object> properties) {
        if (properties.get("workloadProfileName") == null) {
            properties.put("workloadProfileName", "");
        }

        Map<String, Object> template = (Map<String, Object>) properties.get("template");
        for (Map<String, Object> container : listOfMaps(template.get("containers"))) {
            Map<String, Object> resources = container.get("resources") instanceof Map<?, ?> r
                    ? (Map<String, Object>) r : new LinkedHashMap<>();
            resources.putIfAbsent("cpu", DEFAULT_CPU);
            resources.putIfAbsent("memory", DEFAULT_MEMORY);
            container.put("resources", resources);
        }
        if (template.get("scale") instanceof Map<?, ?> s) {
            Map<String, Object> scale = (Map<String, Object>) s;
            scale.putIfAbsent("minReplicas", DEFAULT_MIN_REPLICAS);
            scale.putIfAbsent("maxReplicas", DEFAULT_MAX_REPLICAS);
            template.put("scale", scale);
        }

        Map<String, Object> configuration = properties.get("configuration") instanceof Map<?, ?> c
                ? (Map<String, Object>) c : new LinkedHashMap<>();
        configuration.put("activeRevisionsMode", canonical(asString(configuration.get("activeRevisionsMode")),
                "Single", "Multiple"));
        if (configuration.get("maxInactiveRevisions") == null) {
            configuration.put("maxInactiveRevisions", 0);
        }
        if (configuration.get("ingress") instanceof Map<?, ?> i) {
            Map<String, Object> ingress = (Map<String, Object>) i;
            ingress.put("transport", canonical(asString(ingress.get("transport")),
                    "auto", "http", "http2", "tcp"));
            ingress.putIfAbsent("allowInsecure", false);
            if (ingress.get("ipSecurityRestrictions") == null) {
                ingress.put("ipSecurityRestrictions", new ArrayList<>());
            }
            configuration.put("ingress", ingress);
        }
        properties.put("configuration", configuration);
    }

    private Map<String, Object> toAppArmResponse(ContainerApp app) {
        Map<String, Object> props = deepCopy(app.getProperties());
        String seed = app.storageKey();
        String envId = asString(props.get("managedEnvironmentId"));

        // Both keys are populated with the same client-submitted environment ARM id — the real
        // GET body carries both managedEnvironmentId and environmentId.
        props.put("managedEnvironmentId", envId);
        props.put("environmentId", envId);
        props.put("provisioningState", "Succeeded");
        props.put("runningStatus", "Running");
        props.put("customDomainVerificationId", synthHex(seed + ":cdvid", 64));
        props.put("outboundIpAddresses", synthIpv4List(seed + ":outbound", 2));
        if (props.get("workloadProfileName") == null) {
            props.put("workloadProfileName", "");
        }
        props.put("eventStreamEndpoint", config.effectiveBaseUrl() + app.armId() + "/eventstream");

        String envDomain = environmentDefaultDomain(envId != null ? envId : seed, app.getLocation());
        String revisionSuffix = app.getRevisionSuffix() != null ? app.getRevisionSuffix() : "";
        String latestRevisionName = app.getName() + "--" + revisionSuffix;
        props.put("latestRevisionName", latestRevisionName);
        props.put("latestReadyRevisionName", latestRevisionName);
        props.put("latestRevisionFqdn", latestRevisionName + "." + envDomain);

        if (props.get("configuration") instanceof Map<?, ?> c) {
            @SuppressWarnings("unchecked")
            Map<String, Object> configuration = (Map<String, Object>) c;
            // Read() never looks at configuration.secrets — only listSecrets returns them. Never
            // echo secret values (or KV references) back on GET.
            configuration.remove("secrets");
            if (configuration.get("ingress") instanceof Map<?, ?> i) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ingress = (Map<String, Object>) i;
                ingress.put("fqdn", app.getName() + "." + envDomain);
                ingress.putIfAbsent("customDomains", new ArrayList<>());
                // traffic[] and every other client-submitted field are left exactly as sent —
                // never synthesize revisionName here (§8 rule 1): the provider only sends it when
                // latest_revision=false, and fabricating one corrupts revision_suffix in state.
                configuration.put("ingress", ingress);
            }
            props.put("configuration", configuration);
        }

        if (props.get("template") instanceof Map<?, ?> t) {
            @SuppressWarnings("unchecked")
            Map<String, Object> template = (Map<String, Object>) t;
            template.put("revisionSuffix", revisionSuffix);
            for (Map<String, Object> container : listOfMaps(template.get("containers"))) {
                container.putIfAbsent("ephemeralStorage", "2Gi");
            }
            props.put("template", template);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", app.armId());
        out.put("name", app.getName());
        out.put("type", APP_TYPE);
        out.put("location", app.getLocation());
        out.put("identity", identityForGet(app.getIdentity(), seed));
        if (app.getTags() != null && !app.getTags().isEmpty()) {
            out.put("tags", app.getTags());
        }
        out.put("properties", props);
        return out;
    }

    private Response appNotFound(String rg, String name) {
        return ArmErrors.notFound("The Resource '" + APP_TYPE + "/" + name
                + "' under resource group '" + rg + "' was not found.");
    }

    // ── jobs ──────────────────────────────────────────────────────────────────

    private Response handleJobs(String sub, String rg, String fullPath, String tail,
                                 String method, AzureRequest req) {
        if (tail.matches("jobs(?:[?].*)?") && !fullPath.contains("/resourceGroups/")) {
            return handleJobList(sub, null);
        }
        if (tail.matches("jobs(?:[?].*)?") && "GET".equals(method)) {
            return handleJobList(sub, rg);
        }
        if (tail.matches("jobs/[^/]+/listSecrets(?:[?].*)?") && "POST".equals(method)) {
            return handleJobListSecrets(sub, rg, segment(tail, 1));
        }
        if (tail.matches("jobs/[^/]+(?:[?].*)?")) {
            String name = segment(tail, 1);
            return switch (method) {
                case "GET"    -> handleJobGet(sub, rg, name);
                case "PUT"    -> handleJobCreateOrUpdate(sub, rg, name, req);
                case "DELETE" -> handleJobDelete(sub, rg, name);
                default       -> ArmErrors.error(405, "MethodNotAllowed", "Method not allowed");
            };
        }
        return ArmErrors.notFound("Unsupported Microsoft.App/jobs path: " + tail);
    }

    private Response handleJobCreateOrUpdate(String sub, String rg, String name, AzureRequest req) {
        try {
            JsonNode body = readBody(req.bodyStream());
            Map<String, Object> properties = objectToMap(body.path("properties"));
            @SuppressWarnings("unchecked")
            Map<String, Object> template = properties.get("template") instanceof Map<?, ?> t
                    ? (Map<String, Object>) t : new LinkedHashMap<>();
            properties.put("template", template);

            Response invalid = validateContainers(listOfMaps(template.get("containers")));
            if (invalid != null) {
                return invalid;
            }
            normalizeJobProperties(properties);

            String key = storageKey(sub, rg, name);
            Optional<Job> existing = getJob(key);
            boolean isNew = existing.isEmpty();

            Job job = existing.orElseGet(Job::new);
            if (isNew) {
                job.setSubscriptionId(sub);
                job.setResourceGroup(rg);
                job.setName(name);
                job.setTimeCreated(Instant.now());
            }
            job.setLocation(body.path("location").asText(job.getLocation() == null ? "eastus" : job.getLocation()));
            Map<String, String> tags = parseTags(body.path("tags"));
            job.setTags(tags.isEmpty() ? null : tags);
            job.setIdentity(body.has("identity") ? objectToMap(body.path("identity")) : defaultIdentity());
            job.setProperties(properties);
            job.setProvisioningState("Succeeded");

            putJob(key, job);
            return Response.status(isNew ? 201 : 200)
                    .entity(toJobArmResponse(job))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Error creating/updating container app job %s", name);
            return ArmErrors.error(400, "InvalidRequestContent", "Invalid request: " + e.getMessage());
        }
    }

    private Response handleJobGet(String sub, String rg, String name) {
        return getJob(storageKey(sub, rg, name))
                .map(job -> Response.ok(toJobArmResponse(job)).type(MediaType.APPLICATION_JSON).build())
                .orElseGet(() -> jobNotFound(rg, name));
    }

    private Response handleJobDelete(String sub, String rg, String name) {
        String key = storageKey(sub, rg, name);
        jobStorage.delete(key);
        return Response.status(204).build();
    }

    private Response handleJobList(String sub, String rg) {
        String prefix = rg == null ? (sub + "/").toLowerCase() : (sub + "/" + rg + "/").toLowerCase();
        List<Map<String, Object>> items = new ArrayList<>();
        scanJobs().stream()
                .filter(job -> job.storageKey().toLowerCase().startsWith(prefix))
                .forEach(job -> items.add(toJobArmResponse(job)));
        return Response.ok(Map.of("value", items)).type(MediaType.APPLICATION_JSON).build();
    }

    private Response handleJobListSecrets(String sub, String rg, String name) {
        Optional<Job> found = getJob(storageKey(sub, rg, name));
        if (found.isEmpty()) {
            return jobNotFound(rg, name);
        }
        return Response.ok(listSecretsResponse(secretsOf(found.get().getProperties())))
                .type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * {@code configuration.triggerType} must land on {@code "Manual"} for the ground-truth shape
     * this project sends (the only trigger type in scope) — a missing/miscast value drops
     * {@code manual_trigger_config} from state entirely on read, and since that block is
     * {@code ForceNew}, the next plan shows a full replacement rather than a diff.
     */
    @SuppressWarnings("unchecked")
    private void normalizeJobProperties(Map<String, Object> properties) {
        Map<String, Object> configuration = properties.get("configuration") instanceof Map<?, ?> c
                ? (Map<String, Object>) c : new LinkedHashMap<>();
        configuration.put("triggerType", canonical(asString(configuration.get("triggerType")),
                "Manual", "Event", "Schedule"));
        if (configuration.get("replicaTimeout") == null) {
            configuration.put("replicaTimeout", DEFAULT_REPLICA_TIMEOUT_SECONDS);
        }
        if (configuration.get("replicaRetryLimit") == null) {
            configuration.put("replicaRetryLimit", 0);
        }
        properties.put("configuration", configuration);

        Map<String, Object> template = (Map<String, Object>) properties.get("template");
        for (Map<String, Object> container : listOfMaps(template.get("containers"))) {
            Map<String, Object> resources = container.get("resources") instanceof Map<?, ?> r
                    ? (Map<String, Object>) r : new LinkedHashMap<>();
            resources.putIfAbsent("cpu", DEFAULT_CPU);
            resources.putIfAbsent("memory", DEFAULT_MEMORY);
            container.put("resources", resources);
        }
    }

    private Map<String, Object> toJobArmResponse(Job job) {
        Map<String, Object> props = deepCopy(job.getProperties());
        String seed = job.storageKey();

        props.put("provisioningState", "Succeeded");
        props.put("outboundIpAddresses", synthIpv4List(seed + ":outbound", 2));
        props.put("eventStreamEndpoint", config.effectiveBaseUrl() + job.armId() + "/eventstream");
        if (props.get("workloadProfileName") == null) {
            props.put("workloadProfileName", "");
        }

        if (props.get("configuration") instanceof Map<?, ?> c) {
            @SuppressWarnings("unchecked")
            Map<String, Object> configuration = (Map<String, Object>) c;
            // Same rule as the app: listSecrets is the only secret-read path.
            configuration.remove("secrets");
            props.put("configuration", configuration);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", job.armId());
        out.put("name", job.getName());
        out.put("type", JOB_TYPE);
        out.put("location", job.getLocation());
        out.put("identity", identityForGet(job.getIdentity(), seed));
        if (job.getTags() != null && !job.getTags().isEmpty()) {
            out.put("tags", job.getTags());
        }
        out.put("properties", props);
        return out;
    }

    private Response jobNotFound(String rg, String name) {
        return ArmErrors.notFound("The Resource '" + JOB_TYPE + "/" + name
                + "' under resource group '" + rg + "' was not found.");
    }

    // ── Shared validation ────────────────────────────────────────────────────

    /** Rejects requests the emulator cannot honor; returns null when the body is acceptable. */
    private Response validateContainers(List<Map<String, Object>> containers) {
        if (containers.isEmpty()) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The 'properties.template.containers' is invalid: at least one container is required.");
        }
        for (Map<String, Object> container : containers) {
            if (asString(container.get("image")) == null) {
                return ArmErrors.error(400, "InvalidRequestContent",
                        "Each container requires 'image'.");
            }
        }
        return null;
    }

    /** Case-insensitive match against the canonical values; first value is the default. */
    private static String canonical(String value, String... allowed) {
        if (value != null) {
            for (String candidate : allowed) {
                if (candidate.equalsIgnoreCase(value)) {
                    return candidate;
                }
            }
        }
        return allowed[0];
    }

    // ── Secrets (app + job share the same configuration.secrets[] shape) ──────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> secretsOf(Map<String, Object> properties) {
        if (properties == null || !(properties.get("configuration") instanceof Map<?, ?> c)) {
            return List.of();
        }
        return listOfMaps(((Map<String, Object>) c).get("secrets"));
    }

    /**
     * {@code {"value":[{"name":..., "keyVaultUrl":..., "identity":...}]}} for KV-backed secrets, or
     * {@code {"name":..., "value":...}} for a raw-value secret — {@code keyVaultUrl} echoed
     * byte-identical to what PUT sent (§8 rule 4: any re-casing or versioned/versionless rewrite is
     * a permanent diff, since {@code FlattenContainerAppSecrets} only fills {@code value} when
     * {@code keyVaultUrl} is nil).
     */
    private Map<String, Object> listSecretsResponse(List<Map<String, Object>> secrets) {
        List<Map<String, Object>> value = new ArrayList<>();
        for (Map<String, Object> secret : secrets) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", secret.get("name"));
            Object keyVaultUrl = secret.get("keyVaultUrl");
            if (keyVaultUrl != null) {
                entry.put("keyVaultUrl", keyVaultUrl);
                entry.put("identity", secret.get("identity"));
            } else {
                // A secret submitted with neither a raw value nor a keyVaultUrl (name-only) reads
                // back "" rather than null — the real API never returns a null value here, and a
                // null-vs-empty-string mismatch is itself a plan diff.
                Object rawValue = secret.get("value");
                entry.put("value", rawValue != null ? rawValue : "");
            }
            value.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", value);
        return out;
    }

    // ── Identity block (shared ARM legacy identity shape, §7) ──────────────────

    private static Map<String, Object> defaultIdentity() {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("type", "None");
        identity.put("userAssignedIdentities", null);
        return identity;
    }

    /**
     * Builds the GET-side identity block: the client-submitted {@code type}/
     * {@code userAssignedIdentities} echoed verbatim (unmarshal is case-tolerant per go-azure-sdk's
     * {@code EqualFold} compare, so casing is not normalized here), plus the two read-only fields
     * ARM always adds — {@code principalId}/{@code tenantId}, synthesized deterministically from
     * the resource's storage key so they never change between GETs of the same resource, empty
     * strings when the identity has no system-assigned component (matching the real API's "None"
     * shape).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> identityForGet(Map<String, Object> stored, String seed) {
        Map<String, Object> identity = new LinkedHashMap<>();
        String type = stored != null ? asString(stored.get("type")) : null;
        if (type == null) {
            type = "None";
        }
        identity.put("type", type);

        String lower = type.toLowerCase();
        boolean hasSystem = lower.contains("systemassigned");
        boolean hasUser = lower.contains("userassigned");

        identity.put("principalId", hasSystem ? synthGuid(seed + ":sys-principal") : "");
        identity.put("tenantId", hasSystem ? synthGuid(seed + ":sys-tenant") : "");

        Object uaiRaw = stored != null ? stored.get("userAssignedIdentities") : null;
        if (hasUser && uaiRaw instanceof Map<?, ?> uai && !uai.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<Object, Object>) uai).entrySet()) {
                String resourceId = String.valueOf(entry.getKey());
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("clientId", synthGuid(seed + ":uai-client:" + resourceId));
                value.put("principalId", synthGuid(seed + ":uai-principal:" + resourceId));
                out.put(resourceId, value);
            }
            identity.put("userAssignedIdentities", out);
        } else {
            identity.put("userAssignedIdentities", null);
        }
        return identity;
    }

    // ── Deterministic synthesis of server-computed fields ──────────────────────
    //
    // The client never sends these; the real ARM API assigns them once and they stay fixed for
    // the resource's lifetime. Regenerating a fresh random value on every GET would itself be a
    // terraform plan diff (an O+C attribute must be stable), so each is derived from a
    // java.util.Random seeded by a stable hash of the resource's storage key (or another
    // resource's key/id, for cross-references like the app's environment-derived domain) plus a
    // purpose tag — deterministic across JVM restarts (java.util.Random's algorithm is specified),
    // with no need to persist the generated value separately.

    private static Random seededRandom(String seed) {
        long h = 1125899906842597L;
        for (int i = 0; i < seed.length(); i++) {
            h = 31 * h + seed.charAt(i);
        }
        return new Random(h);
    }

    private static String synthLabel(String seed, int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random r = seededRandom(seed);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String synthHex(String seed, int length) {
        Random r = seededRandom(seed);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(Integer.toHexString(r.nextInt(16)));
        }
        return sb.toString();
    }

    private static String synthOctet(String seed) {
        return String.valueOf(1 + seededRandom(seed).nextInt(254));
    }

    private static String synthIpv4(String seed) {
        Random r = seededRandom(seed);
        return (20 + r.nextInt(200)) + "." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }

    private static List<String> synthIpv4List(String seed, int count) {
        List<String> ips = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ips.add(synthIpv4(seed + ":" + i));
        }
        return ips;
    }

    private static String synthGuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * {@code <label>.<region>.azurecontainerapps.io} — shared between an environment's own
     * {@code defaultDomain} and every app/job's derived FQDNs under it (both seeded from the same
     * environment reference), so an app's {@code ingress.fqdn}/{@code latestRevisionFqdn} sit under
     * a domain that is at least internally consistent for that environment, even though this
     * emulator does not cross-look-up the environment record itself (the reference may point at a
     * different resource group, or a not-yet-created environment in a unit test) — see the class
     * doc's "Out of scope" note and the implementation report for the deliberate simplification.
     */
    private static String environmentDefaultDomain(String environmentSeed, String location) {
        return synthLabel(environmentSeed + ":domain", 10) + "." + safeLocation(location) + ".azurecontainerapps.io";
    }

    private static String safeLocation(String location) {
        return (location == null || location.isBlank()) ? "eastus" : location;
    }

    // ── ResourceIndexContributor ────────────────────────────────────────────────

    @Override
    public boolean indexEnabled() {
        return config.services().containerapps().enabled();
    }

    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        String prefix = (sub + "/" + rg + "/").toLowerCase();
        List<Map<String, Object>> entries = new ArrayList<>();
        scanEnvs().stream()
                .filter(env -> env.storageKey().toLowerCase().startsWith(prefix))
                .forEach(env -> entries.add(env.indexEntry()));
        scanApps().stream()
                .filter(app -> app.storageKey().toLowerCase().startsWith(prefix))
                .forEach(app -> entries.add(app.indexEntry()));
        scanJobs().stream()
                .filter(job -> job.storageKey().toLowerCase().startsWith(prefix))
                .forEach(job -> entries.add(job.indexEntry()));
        return entries;
    }

    // ── Storage helpers ────────────────────────────────────────────────────────

    private Optional<ManagedEnvironment> getEnv(String key) {
        return envStorage.get(key).map(so -> {
            try {
                return MAPPER.readValue(so.data(), ManagedEnvironment.class);
            } catch (Exception e) {
                LOG.warnv("Failed to deserialize managed environment {0}: {1}", key, e.getMessage());
                return null;
            }
        });
    }

    private void putEnv(String key, ManagedEnvironment env) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(env);
            envStorage.put(key, new StoredObject(key, data, Map.of(), Instant.now(), key));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize managed environment: " + key, e);
        }
    }

    private List<ManagedEnvironment> scanEnvs() {
        List<ManagedEnvironment> result = new ArrayList<>();
        envStorage.scan(k -> true).forEach(so -> {
            try {
                ManagedEnvironment env = MAPPER.readValue(so.data(), ManagedEnvironment.class);
                if (env != null) { result.add(env); }
            } catch (Exception e) {
                LOG.debugv("Skipping unreadable managed environment entry: {0}", e.getMessage());
            }
        });
        return result;
    }

    private Optional<ContainerApp> getApp(String key) {
        return appStorage.get(key).map(so -> {
            try {
                return MAPPER.readValue(so.data(), ContainerApp.class);
            } catch (Exception e) {
                LOG.warnv("Failed to deserialize container app {0}: {1}", key, e.getMessage());
                return null;
            }
        });
    }

    private void putApp(String key, ContainerApp app) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(app);
            appStorage.put(key, new StoredObject(key, data, Map.of(), Instant.now(), key));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize container app: " + key, e);
        }
    }

    private List<ContainerApp> scanApps() {
        List<ContainerApp> result = new ArrayList<>();
        appStorage.scan(k -> true).forEach(so -> {
            try {
                ContainerApp app = MAPPER.readValue(so.data(), ContainerApp.class);
                if (app != null) { result.add(app); }
            } catch (Exception e) {
                LOG.debugv("Skipping unreadable container app entry: {0}", e.getMessage());
            }
        });
        return result;
    }

    private Optional<Job> getJob(String key) {
        return jobStorage.get(key).map(so -> {
            try {
                return MAPPER.readValue(so.data(), Job.class);
            } catch (Exception e) {
                LOG.warnv("Failed to deserialize container app job {0}: {1}", key, e.getMessage());
                return null;
            }
        });
    }

    private void putJob(String key, Job job) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(job);
            jobStorage.put(key, new StoredObject(key, data, Map.of(), Instant.now(), key));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize container app job: " + key, e);
        }
    }

    private List<Job> scanJobs() {
        List<Job> result = new ArrayList<>();
        jobStorage.scan(k -> true).forEach(so -> {
            try {
                Job job = MAPPER.readValue(so.data(), Job.class);
                if (job != null) { result.add(job); }
            } catch (Exception e) {
                LOG.debugv("Skipping unreadable container app job entry: {0}", e.getMessage());
            }
        });
        return result;
    }

    // ── Path / body parsing helpers ────────────────────────────────────────────

    private static String extractPath(String fullPath) {
        if (fullPath == null) { return ""; }
        int idx = fullPath.indexOf(MARKER);
        return idx >= 0 ? fullPath.substring(idx + MARKER.length()) : fullPath;
    }

    private static String extractSubscriptionId(String fullPath) {
        return ArmPaths.segmentAfter(fullPath, "subscriptions", "default");
    }

    private static String extractResourceGroup(String fullPath) {
        return ArmPaths.segmentAfter(fullPath, "resourcegroups", "default");
    }

    private static String segment(String path, int index) {
        String[] parts = path.split("[/?]");
        return index < parts.length ? parts[index] : "";
    }

    private static String storageKey(String sub, String rg, String name) {
        return sub + "/" + rg + "/" + name;
    }

    private static Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectToMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.convertValue(node, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> source) {
        if (source == null) {
            return new LinkedHashMap<>();
        }
        return MAPPER.convertValue(MAPPER.valueToTree(source), Map.class);
    }

    private JsonNode readBody(java.io.InputStream stream) {
        try {
            if (stream == null || stream.available() == 0) { return MAPPER.createObjectNode(); }
            return MAPPER.readTree(stream);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /** Wipes all environment/app/job data — used by {@code POST /_admin/reset}. */
    @Override
    public void clear() {
        envStorage.clear();
        appStorage.clear();
        jobStorage.clear();
    }
}
