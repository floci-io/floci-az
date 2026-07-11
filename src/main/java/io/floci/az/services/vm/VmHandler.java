package io.floci.az.services.vm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.vm.VmModels.PowerState;
import io.floci.az.services.vm.VmModels.VirtualMachine;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmPaths;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HTTP handler for Azure Virtual Machines (Microsoft.Compute/virtualMachines) management-plane
 * requests.
 *
 * <h2>Routing</h2>
 * <pre>
 *   GET    subscriptions/{sub}/providers/Microsoft.Compute/virtualMachines                       (list all)
 *   GET    subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines    (list in rg)
 *   PUT    .../virtualMachines/{vm}            create/update
 *   GET    .../virtualMachines/{vm}            (?$expand=instanceView)
 *   PATCH  .../virtualMachines/{vm}            update tags
 *   DELETE .../virtualMachines/{vm}
 *   GET    .../virtualMachines/{vm}/instanceView
 *   POST   .../virtualMachines/{vm}/{start|powerOff|deallocate|restart|redeploy|reapply}
 *   GET    subscriptions/{sub}/providers/Microsoft.Compute/locations/{loc}/operations/{opId}   (LRO status)
 * </pre>
 *
 * <h2>Mocked mode</h2>
 * <p>When {@code floci-az.services.vm.mocked=true} (default), no Docker container is started.
 * VMs transition immediately to {@code provisioningState=Succeeded} / {@code PowerState/running}.
 * Power actions are pure state transitions. This keeps the service usable in CI without Docker.</p>
 */
@ApplicationScoped
public class VmHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(VmHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String COMPUTE_MARKER = "/providers/Microsoft.Compute/";
    private static final String API_VERSION = "2024-11-01";

    private final EmulatorConfig config;
    private final VmContainerManager containerManager;
    private final StorageBackend<String, StoredObject> storage;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vm-readiness-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public VmHandler(EmulatorConfig config,
                     VmContainerManager containerManager,
                     StorageFactory storageFactory) {
        this.config = config;
        this.containerManager = containerManager;
        this.storage = storageFactory.create("vm");
    }

    @PostConstruct
    public void init() {
        if (!config.services().vm().mocked()) {
            startReadinessPoller();
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!config.services().vm().mocked()) {
            scanAll().forEach(vm -> {
                try {
                    containerManager.removeVm(vm);
                } catch (Exception e) {
                    LOG.warnv("Error removing container for VM {0}: {1}", vm.getName(), e.getMessage());
                }
            });
        }
    }

    @Override
    public String getServiceType() { return "vm"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().vm().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .provider("Microsoft.Compute")
                .build();
    }

    @Override
    public boolean canHandle(AzureRequest req) { return "vm".equals(req.serviceType()); }

    @Override
    public Response handle(AzureRequest req) {
        String fullPath = req.resourcePath();
        String method = req.method().toUpperCase();
        String tail = extractComputePath(fullPath);

        LOG.debugf("VmHandler: %s %s (tail=%s)", method, fullPath, tail);

        // ── LRO operation status (returns terminal Succeeded immediately) ──────
        if (tail.matches("locations/[^/]+/operation(?:s|Results)/[^/?]+.*")) {
            return Response.ok(Map.of("status", "Succeeded")).type("application/json").build();
        }

        // ── List in subscription ───────────────────────────────────────────────
        if (tail.matches("virtualMachines(?:[?].*)?") && !fullPath.contains("/resourceGroups/")) {
            return handleListSubscription(extractSubscriptionId(fullPath), expandsInstanceView(req));
        }

        // ── List in resource group ──────────────────────────────────────────────
        if (tail.matches("virtualMachines(?:[?].*)?") && "GET".equals(method)) {
            return handleListByResourceGroup(extractSubscriptionId(fullPath),
                    extractResourceGroup(fullPath), expandsInstanceView(req));
        }

        String sub = extractSubscriptionId(fullPath);
        String rg = extractResourceGroup(fullPath);

        // ── instanceView ──────────────────────────────────────────────────────
        if (tail.matches("virtualMachines/[^/]+/instanceView(?:[?].*)?") && "GET".equals(method)) {
            return handleInstanceView(sub, rg, segment(tail, 1));
        }

        // ── Power actions ───────────────────────────────────────────────────────
        if (tail.matches("virtualMachines/[^/]+/(start|powerOff|deallocate|restart|redeploy|reapply)(?:[?].*)?")
                && "POST".equals(method)) {
            return handlePowerAction(sub, rg, segment(tail, 1), segment(tail, 2));
        }

        // ── Single VM CRUD ────────────────────────────────────────────────────────
        if (tail.matches("virtualMachines/[^/]+(?:[?].*)?")) {
            String vmName = segment(tail, 1);
            return switch (method) {
                case "GET"    -> handleGet(sub, rg, vmName, expandsInstanceView(req));
                case "PUT"    -> handleCreateOrUpdate(sub, rg, vmName, req);
                case "PATCH"  -> handleUpdateTags(sub, rg, vmName, req);
                case "DELETE" -> handleDelete(sub, rg, vmName);
                default       -> methodNotAllowed();
            };
        }

        return armNotFound("Unsupported Microsoft.Compute path: " + tail);
    }

    // ── CRUD ───────────────────────────────────────────────────────────────────

    private Response handleCreateOrUpdate(String sub, String rg, String vmName, AzureRequest req) {
        try {
            JsonNode body = readBody(req.bodyStream());
            String location = body.path("location").asText("eastus");
            Map<String, String> tags = parseTags(body.path("tags"));
            Map<String, Object> properties = objectToMap(body.path("properties"));

            String key = storageKey(sub, rg, vmName);
            Optional<VirtualMachine> existing = getVm(key);
            boolean isNew = existing.isEmpty();

            VirtualMachine vm = existing.orElseGet(VirtualMachine::new);
            if (isNew) {
                vm.setSubscriptionId(sub);
                vm.setResourceGroup(rg);
                vm.setName(vmName);
                vm.setVmId(UUID.randomUUID().toString());
                vm.setTimeCreated(Instant.now());
            }
            vm.setLocation(location);
            vm.setTags(tags.isEmpty() ? null : tags);
            vm.setProperties(properties);

            if (config.services().vm().mocked()) {
                // Mocked mode: VM is provisioned and powered on immediately (no Docker).
                vm.setProvisioningState("Succeeded");
                if (isNew || vm.getPowerState() == null) {
                    vm.setPowerState(PowerState.RUNNING.code());
                }
            } else if (isNew) {
                // Real mode: launch a backing container; the readiness poller flips the VM to
                // Succeeded once it is running. Docker failures degrade to mocked-style state.
                vm.setProvisioningState("Creating");
                vm.setPowerState(PowerState.STARTING.code());
                try {
                    containerManager.startVm(vm);
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to start container for VM %s; degrading to mocked state", vmName);
                    vm.setProvisioningState("Succeeded");
                    vm.setPowerState(PowerState.RUNNING.code());
                }
            } else {
                // Real mode update: keep the existing container and power state.
                vm.setProvisioningState("Succeeded");
            }

            putVm(key, vm);
            return Response.status(isNew ? 201 : 200)
                    .entity(toArmResponse(vm, false))
                    .type("application/json")
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Error creating/updating VM %s", vmName);
            return badRequest("Invalid request: " + e.getMessage());
        }
    }

    private Response handleGet(String sub, String rg, String vmName, boolean expandInstanceView) {
        return getVm(storageKey(sub, rg, vmName))
                .map(vm -> Response.ok(toArmResponse(vm, expandInstanceView)).type("application/json").build())
                .orElseGet(() -> armNotFound("The Resource 'Microsoft.Compute/virtualMachines/" + vmName
                        + "' under resource group '" + rg + "' was not found."));
    }

    private Response handleUpdateTags(String sub, String rg, String vmName, AzureRequest req) {
        String key = storageKey(sub, rg, vmName);
        Optional<VirtualMachine> found = getVm(key);
        if (found.isEmpty()) {
            return armNotFound("Virtual machine '" + vmName + "' not found.");
        }
        try {
            JsonNode body = readBody(req.bodyStream());
            VirtualMachine vm = found.get();
            Map<String, String> tags = parseTags(body.path("tags"));
            vm.setTags(tags.isEmpty() ? null : tags);
            putVm(key, vm);
            return Response.ok(toArmResponse(vm, false)).type("application/json").build();
        } catch (Exception e) {
            return badRequest("Invalid request: " + e.getMessage());
        }
    }

    private Response handleDelete(String sub, String rg, String vmName) {
        String key = storageKey(sub, rg, vmName);
        if (!config.services().vm().mocked()) {
            getVm(key).ifPresent(vm -> {
                try {
                    containerManager.removeVm(vm);
                } catch (Exception e) {
                    LOG.warnv("Error removing container for VM {0}: {1}", vmName, e.getMessage());
                }
            });
        }
        storage.delete(key);
        // Delete synchronously with 204 No Content. The azurerm provider's virtualmachines
        // DeleteThenPoll treats a 202/200 as a long-running operation and polls the collection
        // forever against the emulator; a terminal 204 signals the delete is complete so
        // `terraform destroy` proceeds. 204 is also idempotent for an already-absent VM.
        return Response.status(204).build();
    }

    private Response handleListSubscription(String sub, boolean expandInstanceView) {
        String prefix = sub + "/";
        List<Map<String, Object>> items = new ArrayList<>();
        scanAll().stream()
                .filter(vm -> vm.storageKey().startsWith(prefix))
                .forEach(vm -> items.add(toArmResponse(vm, expandInstanceView)));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    private Response handleListByResourceGroup(String sub, String rg, boolean expandInstanceView) {
        String prefix = (sub + "/" + rg + "/").toLowerCase();
        List<Map<String, Object>> items = new ArrayList<>();
        scanAll().stream()
                .filter(vm -> vm.storageKey().toLowerCase().startsWith(prefix))
                .forEach(vm -> items.add(toArmResponse(vm, expandInstanceView)));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    private Response handleInstanceView(String sub, String rg, String vmName) {
        // The instanceView endpoint returns the bare VirtualMachineInstanceView object
        // (no id/name/type wrapper) — see Compute spec VirtualMachine_Get_InstanceView.
        return getVm(storageKey(sub, rg, vmName))
                .map(vm -> Response.ok(instanceViewBody(vm)).type("application/json").build())
                .orElseGet(() -> armNotFound("Virtual machine '" + vmName + "' not found."));
    }

    private Response handlePowerAction(String sub, String rg, String vmName, String action) {
        String key = storageKey(sub, rg, vmName);
        Optional<VirtualMachine> found = getVm(key);
        if (found.isEmpty()) {
            return armNotFound("Virtual machine '" + vmName + "' not found.");
        }
        VirtualMachine vm = found.get();
        PowerState target = switch (action) {
            case "powerOff"   -> PowerState.STOPPED;
            case "deallocate" -> PowerState.DEALLOCATED;
            default           -> PowerState.RUNNING;  // start, restart, redeploy, reapply
        };

        if (!config.services().vm().mocked()) {
            // Map the Azure power action onto the backing container. Failures are non-fatal:
            // the recorded power state still transitions so the control plane stays consistent.
            try {
                switch (action) {
                    case "powerOff", "deallocate"        -> containerManager.stopContainer(vm);
                    case "restart", "redeploy", "reapply" -> containerManager.restartContainer(vm);
                    default                               -> containerManager.startContainer(vm);
                }
            } catch (Exception e) {
                LOG.warnv("Power action {0} on VM {1} failed: {2}", action, vmName, e.getMessage());
            }
        }

        vm.setPowerState(target.code());
        vm.setProvisioningState("Succeeded");
        putVm(key, vm);
        return acceptedWithAsync(sub, vm.getLocation());
    }

    // ── ARM response builders ────────────────────────────────────────────────────

    private Map<String, Object> toArmResponse(VirtualMachine vm, boolean expandInstanceView) {
        Map<String, Object> props = vm.getProperties() != null
                ? new LinkedHashMap<>(vm.getProperties())
                : new LinkedHashMap<>();
        props.put("vmId", vm.getVmId());
        props.put("provisioningState", vm.getProvisioningState());
        if (vm.getTimeCreated() != null) {
            props.put("timeCreated", OffsetDateTime.ofInstant(vm.getTimeCreated(), ZoneOffset.UTC).toString());
        }
        if (expandInstanceView) {
            props.put("instanceView", instanceViewBody(vm));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", vm.armId());
        out.put("name", vm.getName());
        out.put("type", "Microsoft.Compute/virtualMachines");
        out.put("location", vm.getLocation());
        if (vm.getTags() != null && !vm.getTags().isEmpty()) {
            out.put("tags", vm.getTags());
        }
        out.put("properties", props);
        return out;
    }

    private Map<String, Object> instanceViewBody(VirtualMachine vm) {
        PowerState power = PowerState.fromCode(vm.getPowerState());
        String computerName = osProfileComputerName(vm);

        List<Map<String, Object>> statuses = new ArrayList<>();
        statuses.add(Map.of(
                "code", "ProvisioningState/" + lower(vm.getProvisioningState()),
                "level", "Info",
                "displayStatus", "Provisioning succeeded"));
        statuses.add(Map.of(
                "code", power.statusCode(),
                "level", "Info",
                "displayStatus", power.displayStatus()));

        Map<String, Object> view = new LinkedHashMap<>();
        if (computerName != null) {
            view.put("computerName", computerName);
        }
        view.put("osName", "Linux");
        view.put("statuses", statuses);
        return view;
    }

    @SuppressWarnings("unchecked")
    private static String osProfileComputerName(VirtualMachine vm) {
        if (vm.getProperties() == null) { return vm.getName(); }
        Object osProfile = vm.getProperties().get("osProfile");
        if (osProfile instanceof Map<?, ?> m) {
            Object cn = ((Map<String, Object>) m).get("computerName");
            if (cn != null) { return cn.toString(); }
        }
        return vm.getName();
    }

    private Response acceptedWithAsync(String sub, String location) {
        String loc = (location == null || location.isBlank()) ? "eastus" : location;
        String url = config.effectiveBaseUrl() + "/subscriptions/" + sub
                + "/providers/Microsoft.Compute/locations/" + loc
                + "/operations/" + UUID.randomUUID() + "?api-version=" + API_VERSION + "&monitor=true";
        return Response.status(202)
                .header("Azure-AsyncOperation", url)
                .header("Location", url)
                .build();
    }

    // ── Readiness poller (non-mocked mode) ───────────────────────────────────────

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                scanAll().forEach(vm -> {
                    if ("Creating".equals(vm.getProvisioningState()) && containerManager.isRunning(vm)) {
                        vm.setProvisioningState("Succeeded");
                        vm.setPowerState(PowerState.RUNNING.code());
                        putVm(vm.storageKey(), vm);
                        LOG.infov("VM {0} container is running; provisioningState=Succeeded", vm.getName());
                    }
                });
            } catch (Exception e) {
                LOG.error("Error in VM readiness poller", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    // ── Storage helpers ────────────────────────────────────────────────────────

    private Optional<VirtualMachine> getVm(String key) {
        return storage.get(key).map(so -> {
            try {
                return MAPPER.readValue(so.data(), VirtualMachine.class);
            } catch (Exception e) {
                LOG.warnv("Failed to deserialize VM {0}: {1}", key, e.getMessage());
                return null;
            }
        });
    }

    private void putVm(String key, VirtualMachine vm) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(vm);
            storage.put(key, new StoredObject(key, data, Map.of(), Instant.now(), key));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize VM: " + key, e);
        }
    }

    private List<VirtualMachine> scanAll() {
        List<VirtualMachine> result = new ArrayList<>();
        storage.scan(k -> true).forEach(so -> {
            try {
                VirtualMachine vm = MAPPER.readValue(so.data(), VirtualMachine.class);
                if (vm != null) { result.add(vm); }
            } catch (Exception e) {
                LOG.debugv("Skipping unreadable VM entry: {0}", e.getMessage());
            }
        });
        return result;
    }

    // ── Path / body parsing helpers ──────────────────────────────────────────────

    private static String extractComputePath(String fullPath) {
        if (fullPath == null) { return ""; }
        int idx = fullPath.indexOf(COMPUTE_MARKER);
        return idx >= 0 ? fullPath.substring(idx + COMPUTE_MARKER.length()) : fullPath;
    }

    private static String extractSubscriptionId(String fullPath) {
        return segmentAfter(fullPath, "subscriptions");
    }

    private static String extractResourceGroup(String fullPath) {
        return segmentAfter(fullPath, "resourcegroups");
    }

    private static String segmentAfter(String fullPath, String marker) {
        return ArmPaths.segmentAfter(fullPath, marker, "default");
    }

    private static String segment(String path, int index) {
        String[] parts = path.split("[/?]");
        return index < parts.length ? parts[index] : "";
    }

    private static boolean expandsInstanceView(AzureRequest req) {
        String expand = req.queryParams() == null ? null : req.queryParams().get("$expand");
        return expand != null && expand.toLowerCase().contains("instanceview");
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    private static String storageKey(String sub, String rg, String vmName) {
        return sub + "/" + rg + "/" + vmName;
    }

    private static Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectToMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.convertValue(node, Map.class);
    }

    private JsonNode readBody(java.io.InputStream stream) {
        try {
            if (stream == null || stream.available() == 0) { return MAPPER.createObjectNode(); }
            return MAPPER.readTree(stream);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    // ── Standard ARM error responses ──────────────────────────────────────────────

    private static Response armNotFound(String message) {
        return ArmErrors.notFound(message);
    }

    private static Response badRequest(String message) {
        return ArmErrors.error(400, "InvalidRequest", message);
    }

    private static Response methodNotAllowed() {
        return ArmErrors.error(405, "MethodNotAllowed", "Method not allowed");
    }

    /** Wipes all VM data — used by {@code POST /_admin/reset}. */
    public void clearAll() {
        storage.clear();
    }
}
