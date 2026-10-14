package io.floci.az.services.arm;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.ArmRouter;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.directory.ResourceGroup;
import io.floci.az.core.directory.ResourceGroups;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code Microsoft.Resources/resourceGroups}: the group's own operations, and the cascade a delete runs.
 *
 * <p>Wire contract, Resources spec 2024-11-01 (the version the az CLI pins; azurerm pins 2023-07-01, whose
 * operations and codes are the same): {@code ResourceGroups_CreateOrUpdate} answers 200 or 201,
 * {@code ResourceGroups_CheckExistence} 204 or 404, {@code ResourceGroups_Update} (PATCH) 200 and keeps every
 * field the body leaves out, and {@code ResourceGroups_Delete} 200 or 202 as a long-running operation polled
 * through {@code Location}. What a client could have relied on keeps its old answer unless a
 * {@code parity-overrides} switch says otherwise:
 *
 * <ul>
 *   <li>always: tags and {@code managedBy} are kept, {@code PATCH} updates them, {@code HEAD} answers, and
 *       names match case-insensitively</li>
 *   <li>{@link EmulatorConfig#resourceGroupSemantics()}: a re-PUT is 200, and a {@code GET} of a missing group
 *       is {@code 404 ResourceGroupNotFound}; without it every PUT is 201 and a missing group is the generic
 *       {@code 404 ResourceNotFound}</li>
 *   <li>{@link EmulatorConfig#resourceGroupDelete()}: {@code DELETE} deletes every resource in the group,
 *       deepest first, through {@link ArmRouter#dispatchInternal} so each handler applies its own side effects,
 *       then answers 202 with a {@code Location} to poll and a short {@code Retry-After}; a missing group is
 *       {@code 404 ResourceGroupNotFound}. While the delete runs, a PUT or PATCH of a resource under the group
 *       is refused with {@code 409 ResourceGroupBeingDeleted}, so nothing created meanwhile outlives the
 *       group. Without it {@code DELETE} answers 200, deletes only the group, and
 *       answers 200 for a missing group too</li>
 * </ul>
 *
 * <p>The cascade runs before the 202 is sent, so the operation is complete by the first poll: the
 * {@code Location} answers 200 with an empty body, the shape Azure's own poll ends with.
 */
@ApplicationScoped
public class ResourceGroupService {

    private static final Logger LOG = Logger.getLogger(ResourceGroupService.class);

    /** Seconds a client waits before polling a delete; short, because the operation is already complete. */
    static final String RETRY_AFTER_SECONDS = "1";

    /** Completed delete operations kept for polling; older ones are answered 404. */
    private static final int MAX_TRACKED_OPERATIONS = 1000;

    private final EmulatorConfig config;
    private final ResourceGroups groups;
    private final Instance<ArmRouter> router;
    private final boolean semantics;
    private final boolean azureDelete;

    /** Guarded by itself; operation id to the subscription it ran in, oldest first. */
    private final Map<String, String> completedDeletes = new LinkedHashMap<>();

    @Inject
    public ResourceGroupService(EmulatorConfig config, ResourceGroups groups, Instance<ArmRouter> router) {
        this.config = config;
        this.groups = groups;
        this.router = router;
        this.semantics = config.resourceGroupSemantics();
        this.azureDelete = config.resourceGroupDelete();
    }

    /** {@code GET subscriptions/{sub}/resourceGroups}. */
    public Response list(String subscriptionId) {
        return Response.ok(Map.of("value", groups.list(subscriptionId).stream().map(ResourceGroup::body).toList()))
            .build();
    }

    /**
     * One group's operations.
     *
     * @param resources the ids of every resource in the subscription, read only when a delete cascades
     */
    public Response handle(AzureRequest req, String method, String subscriptionId, String name,
                           Supplier<List<String>> resources) {
        return switch (method) {
            case "PUT" -> createOrUpdate(req, subscriptionId, name);
            case "GET" -> get(subscriptionId, name);
            case "HEAD" -> Response.status(groups.exists(subscriptionId, name) ? 204 : 404).build();
            case "PATCH" -> update(req, subscriptionId, name);
            case "DELETE" -> delete(req, subscriptionId, name, resources);
            default -> Response.status(405).build();
        };
    }

    /**
     * {@code GET subscriptions/{sub}/operationresults/{id}}: the poll of a delete, complete by the time it is
     * asked for.
     */
    public Response operationResult(String subscriptionId, String operationId) {
        String owner;
        synchronized (completedDeletes) {
            owner = completedDeletes.get(operationId);
        }
        if (owner == null || !owner.equalsIgnoreCase(subscriptionId)) {
            return ArmErrors.notFound("Resource not found: operationresults/" + operationId);
        }
        return Response.ok().build();
    }

    private Response createOrUpdate(AzureRequest req, String subscriptionId, String name) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        ResourceGroup group = new ResourceGroup(subscriptionId, name,
            ArmJson.string(body, "location", defaultLocation(req)),
            tags(body.get("tags")), body.get("managedBy") instanceof String managedBy ? managedBy : null,
            ResourceGroup.SUCCEEDED);
        boolean created = groups.put(group);
        LOG.infov("ARM: {0} resource group {1}", created ? "created" : "updated", name);
        return Response.status(created || !semantics ? 201 : 200).entity(group.body()).build();
    }

    private Response get(String subscriptionId, String name) {
        return groups.get(subscriptionId, name)
            .map(group -> Response.ok(group.body()).build())
            .orElseGet(() -> ArmErrors.resourceGroupNotFound(name));
    }

    /** The default location of the cloud the request resolved to: the one an implicit group gets too. */
    private static String defaultLocation(AzureRequest req) {
        CloudProfile cloud = req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD)
            : req.scope().cloud();
        return cloud.effectiveDefaultLocation();
    }

    /** {@code ResourceGroupPatchable}: {@code tags} and {@code managedBy} replace what they name; nothing else changes. */
    private Response update(AzureRequest req, String subscriptionId, String name) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        Optional<ResourceGroup> updated = groups.update(subscriptionId, name, group -> new ResourceGroup(
            group.subscriptionId(), group.name(), group.location(),
            body.get("tags") instanceof Map<?, ?> ? tags(body.get("tags")) : group.tags(),
            body.get("managedBy") instanceof String managedBy ? managedBy : group.managedBy(),
            group.provisioningState()));
        return updated.map(group -> Response.ok(group.body()).build())
            .orElseGet(() -> ArmErrors.resourceGroupNotFound(name));
    }

    private Response delete(AzureRequest req, String subscriptionId, String name, Supplier<List<String>> resources) {
        if (!azureDelete) {
            groups.remove(subscriptionId, name);
            return Response.ok().build();
        }
        // Marked before the resources are listed: a write that would otherwise land between the listing and
        // the removal is refused by ResourceGroupRule, and one already running is waited for.
        if (!groups.beginDelete(subscriptionId, name)) {
            return ArmErrors.resourceGroupNotFound(name);
        }
        try {
            cascade(req, subscriptionId, name, resources.get());
            groups.remove(subscriptionId, name);
        } finally {
            groups.endDelete(subscriptionId, name);
        }
        String operationId = UUID.randomUUID().toString();
        record(operationId, subscriptionId);
        String location = RequestUrls.resolveBaseUrl(req, config) + "/subscriptions/" + subscriptionId
            + "/operationresults/" + operationId + apiVersionQuery(req);
        return Response.status(202)
            .header("Location", location)
            .header("Retry-After", RETRY_AFTER_SECONDS)
            .build();
    }

    /**
     * Deletes every resource in the group, the deepest ids first so a child goes before its parent. A resource
     * that is already gone (404) counts as deleted; any other failure is logged and the cascade goes on, so one
     * stuck resource never leaves the group behind.
     */
    private void cascade(AzureRequest req, String subscriptionId, String name, List<String> resourceIds) {
        Map<String, ArmResourceId> targets = new LinkedHashMap<>();
        for (String resourceId : resourceIds) {
            ArmResourceId.parse(resourceId)
                .filter(id -> subscriptionId.equalsIgnoreCase(id.subscriptionId()))
                .filter(id -> name.equalsIgnoreCase(id.resourceGroup()) && id.providerNamespace() != null)
                .ifPresent(id -> targets.putIfAbsent(id.canonicalKey(), id));
        }
        List<ArmResourceId> ordered = targets.values().stream()
            .sorted(Comparator.comparingInt(ResourceGroupService::depth).reversed()
                .thenComparing(ArmResourceId::canonicalKey))
            .toList();
        LOG.infov("ARM: deleting resource group {0} and its {1} resource(s)", name, ordered.size());
        for (ArmResourceId id : ordered) {
            int status = router.get().dispatchInternal("DELETE", id.id(), Map.of(), req).getStatus();
            if (status / 100 != 2 && status != 404) {
                LOG.warnv("ARM: deleting {0} with resource group {1} answered {2}; going on", id.id(), name, status);
            }
        }
    }

    private static int depth(ArmResourceId id) {
        return id.id().split("/").length;
    }

    private void record(String operationId, String subscriptionId) {
        synchronized (completedDeletes) {
            completedDeletes.put(operationId, subscriptionId);
            if (completedDeletes.size() > MAX_TRACKED_OPERATIONS) {
                completedDeletes.remove(completedDeletes.keySet().iterator().next());
            }
        }
    }

    private static String apiVersionQuery(AzureRequest req) {
        String apiVersion = req.queryParams() == null ? null : req.queryParams().get("api-version");
        return apiVersion == null || apiVersion.isBlank() ? "" : "?api-version=" + apiVersion;
    }

    private static Map<String, Object> tags(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> tags = new LinkedHashMap<>();
        map.forEach((key, value) -> tags.put(String.valueOf(key), value));
        return tags;
    }
}
