package io.floci.az.services.arm;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.directory.ResourceGroup;
import io.floci.az.core.directory.ResourceGroups;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code Microsoft.Resources/resourceGroups}: the group's own operations.
 *
 * <p>Wire contract, Resources spec 2024-11-01 (the version the az CLI pins; azurerm pins 2023-07-01, whose
 * operations and codes are the same): {@code ResourceGroups_CheckExistence} answers 204 or 404, and
 * {@code ResourceGroups_Update} (PATCH) answers 200 and keeps every field the body leaves out. Tags and
 * {@code managedBy} are kept, and names match case-insensitively. Every PUT answers 201 and {@code DELETE}
 * 200, a missing group included, as they always have.
 */
@ApplicationScoped
public class ResourceGroupService {

    private static final Logger LOG = Logger.getLogger(ResourceGroupService.class);

    private final ResourceGroups groups;

    @Inject
    public ResourceGroupService(ResourceGroups groups) {
        this.groups = groups;
    }

    /** {@code GET subscriptions/{sub}/resourceGroups}. */
    public Response list(String subscriptionId) {
        return Response.ok(Map.of("value", groups.list(subscriptionId).stream().map(ResourceGroup::body).toList()))
            .build();
    }

    /** One group's operations. */
    public Response handle(AzureRequest req, String method, String subscriptionId, String name) {
        return switch (method) {
            case "PUT" -> createOrUpdate(req, subscriptionId, name);
            case "GET" -> get(subscriptionId, name);
            case "HEAD" -> Response.status(groups.exists(subscriptionId, name) ? 204 : 404).build();
            case "PATCH" -> update(req, subscriptionId, name);
            case "DELETE" -> {
                groups.remove(subscriptionId, name);
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    private Response createOrUpdate(AzureRequest req, String subscriptionId, String name) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        ResourceGroup group = new ResourceGroup(subscriptionId, name, ArmJson.string(body, "location", "eastus"),
            tags(body.get("tags")), body.get("managedBy") instanceof String managedBy ? managedBy : null,
            ResourceGroup.SUCCEEDED);
        boolean created = groups.put(group);
        LOG.infov("ARM: {0} resource group {1}", created ? "created" : "updated", name);
        return Response.status(201).entity(group.body()).build();
    }

    private Response get(String subscriptionId, String name) {
        return groups.get(subscriptionId, name)
            .map(group -> Response.ok(group.body()).build())
            .orElseGet(() -> ArmErrors.notFound("Resource not found: resourceGroups/" + name));
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

    private static Map<String, Object> tags(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> tags = new LinkedHashMap<>();
        map.forEach((key, value) -> tags.put(String.valueOf(key), value));
        return tags;
    }
}
