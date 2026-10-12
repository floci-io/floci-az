package io.floci.az.services.resourcegraph;

import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.ResourceGroup;
import io.floci.az.core.directory.ResourceGroups;
import io.floci.az.core.directory.Subscription;
import io.floci.az.services.arm.ArmHandler;
import io.floci.az.services.policy.PolicyStore;
import io.floci.az.services.resourcegraph.kql.KqlEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Resource Graph tables, assembled from the emulator's stores on every query:
 * <ul>
 *   <li>{@code resources}: every resource the ARM resource index lists, across the subscriptions the
 *       directory knows;</li>
 *   <li>{@code resourcecontainers}: subscriptions and resource groups (management groups are not modelled,
 *       so a subscription's {@code managementGroupAncestorsChain} is empty);</li>
 *   <li>{@code policyresources}: policy definitions, set definitions, assignments and exemptions;</li>
 *   <li>{@code authorizationresources}: empty, since role assignments and definitions are not stored.</li>
 * </ul>
 * Rows carry the columns Resource Graph publishes, with {@code type}, {@code resourceGroup} and
 * {@code location} lowercased the way the service normalises them.
 */
@ApplicationScoped
public class GraphTables implements KqlEngine.Tables {

    static final Set<String> TABLES = Set.of("resources", "resourcecontainers", "policyresources", "authorizationresources");

    private final ArmHandler armHandler;
    private final AzureDirectory directory;
    private final ResourceGroups resourceGroups;
    private final PolicyStore policyStore;

    @Inject
    public GraphTables(ArmHandler armHandler, AzureDirectory directory, ResourceGroups resourceGroups, PolicyStore policyStore) {
        this.armHandler = armHandler;
        this.directory = directory;
        this.resourceGroups = resourceGroups;
        this.policyStore = policyStore;
    }

    @Override
    public List<Map<String, Object>> rows(String table) {
        return switch (table.toLowerCase(Locale.ROOT)) {
            case "resources" -> resources();
            case "resourcecontainers" -> resourceContainers();
            case "policyresources" -> policyResources();
            case "authorizationresources" -> List.of();
            default -> null;
        };
    }

    /** The subscriptions the directory knows plus any a stored resource group or policy resource names. */
    List<String> subscriptionIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Subscription subscription : directory.subscriptions()) {
            ids.add(subscription.id());
        }
        for (Map<String, Object> policyResource : policyResources()) {
            if (policyResource.get("subscriptionId") instanceof String id) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    private List<Map<String, Object>> resources() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String subscriptionId : subscriptionIds()) {
            String tenantId = tenantOf(subscriptionId);
            for (Map<String, Object> resource : armHandler.indexedResources(subscriptionId, null, true)) {
                Map<String, Object> row = row(ArmResources.stripInternal(resource), subscriptionId);
                row.putIfAbsent("tenantId", tenantId);
                if (row.get("tenantId") == null) {
                    row.put("tenantId", tenantId);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** The tenant a subscription belongs to: its directory entry's, else the default tenant. */
    private String tenantOf(String subscriptionId) {
        Subscription subscription = subscriptionId == null ? null : directory.subscription(subscriptionId).orElse(null);
        return subscription == null || subscription.tenantId() == null ? directory.defaultTenantId() : subscription.tenantId();
    }

    private List<Map<String, Object>> resourceContainers() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String subscriptionId : subscriptionIds()) {
            Subscription subscription = directory.subscription(subscriptionId).orElse(null);
            String tenantId = tenantOf(subscriptionId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "/subscriptions/" + subscriptionId);
            row.put("name", subscription == null ? subscriptionId : subscription.displayName());
            row.put("type", "microsoft.resources/subscriptions");
            row.put("tenantId", tenantId);
            row.put("kind", null);
            row.put("location", null);
            row.put("resourceGroup", null);
            row.put("subscriptionId", subscriptionId);
            row.put("managedBy", null);
            row.put("sku", null);
            row.put("plan", null);
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("subscriptionId", subscriptionId);
            properties.put("displayName", row.get("name"));
            properties.put("state", subscription == null ? "Enabled" : subscription.state().toString());
            properties.put("managementGroupAncestorsChain", List.of());
            row.put("properties", properties);
            row.put("tags", null);
            row.put("identity", null);
            row.put("zones", null);
            row.put("extendedLocation", null);
            rows.add(row);
            for (ResourceGroup group : resourceGroups.list(subscriptionId)) {
                Map<String, Object> groupRow = new LinkedHashMap<>();
                groupRow.put("id", "/subscriptions/" + subscriptionId + "/resourceGroups/" + group.name());
                groupRow.put("name", group.name());
                groupRow.put("type", "microsoft.resources/subscriptions/resourcegroups");
                groupRow.put("tenantId", tenantId);
                groupRow.put("kind", null);
                groupRow.put("location", group.location() == null ? null : group.location().toLowerCase(Locale.ROOT));
                groupRow.put("resourceGroup", group.name().toLowerCase(Locale.ROOT));
                groupRow.put("subscriptionId", subscriptionId);
                groupRow.put("managedBy", group.managedBy());
                groupRow.put("sku", null);
                groupRow.put("plan", null);
                groupRow.put("properties", Map.of("provisioningState", group.provisioningState() == null ? "Succeeded" : group.provisioningState()));
                groupRow.put("tags", group.tags() == null || group.tags().isEmpty() ? null : group.tags());
                groupRow.put("identity", null);
                groupRow.put("zones", null);
                groupRow.put("extendedLocation", null);
                rows.add(groupRow);
            }
        }
        return rows;
    }

    private List<Map<String, Object>> policyResources() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<Map<String, Object>> resources : List.of(policyStore.listDefinitions(), policyStore.listSetDefinitions(),
                policyStore.listAssignments(), policyStore.listExemptions())) {
            for (Map<String, Object> resource : resources) {
                Map<String, Object> clean = new LinkedHashMap<>(resource);
                clean.keySet().removeIf(key -> key.startsWith("_"));
                Map<String, Object> row = row(clean, null);
                row.put("kind", "policy");
                if (row.get("tenantId") == null) {
                    row.put("tenantId", tenantOf(row.get("subscriptionId") instanceof String s ? s : null));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** An ARM resource body as a Resource Graph row: the published columns, type and group lowercased. */
    static Map<String, Object> row(Map<String, Object> resource, String subscriptionId) {
        String id = String.valueOf(resource.get("id"));
        ArmResourceId parsed = ArmResourceId.parse(id).orElse(null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", resource.get("name"));
        row.put("type", resource.get("type") == null ? null : String.valueOf(resource.get("type")).toLowerCase(Locale.ROOT));
        row.put("tenantId", resource.get("tenantId"));
        row.put("kind", resource.get("kind"));
        row.put("location", resource.get("location") == null ? null : String.valueOf(resource.get("location")).toLowerCase(Locale.ROOT));
        row.put("resourceGroup", parsed == null || parsed.resourceGroup() == null ? null : parsed.resourceGroup().toLowerCase(Locale.ROOT));
        row.put("subscriptionId", parsed != null && parsed.subscriptionId() != null ? parsed.subscriptionId() : subscriptionId);
        row.put("managedBy", resource.get("managedBy"));
        row.put("sku", resource.get("sku"));
        row.put("plan", resource.get("plan"));
        row.put("properties", resource.getOrDefault("properties", Map.of()));
        row.put("tags", resource.get("tags"));
        row.put("identity", resource.get("identity"));
        row.put("zones", resource.get("zones"));
        row.put("extendedLocation", resource.get("extendedLocation"));
        row.put("systemData", resource.get("systemData"));
        return row;
    }
}
