package io.floci.az.services.containerapps;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted models for {@code Microsoft.App} — managed environments, container apps, and jobs.
 *
 * <p>Each model stores its submitted {@code identity} and {@code properties} blocks verbatim
 * (including secrets — a container app's KV references and, when present, raw secret values), so
 * GET round-trips faithfully for the azurerm provider and the Azure SDKs. Secrets are stripped by
 * {@link ContainerAppsHandler}'s response builders on every read path, never here; the same
 * builders synthesize the handful of server-computed fields (domains, IPs, revision names) that
 * the client never sends, deterministically from each resource's storage key so repeated GETs of
 * the same resource never drift (a fresh random value each request is itself a
 * {@code terraform plan} diff).</p>
 */
public class ContainerAppsModels {

    /** {@code Microsoft.App/managedEnvironments}. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ManagedEnvironment {
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private String location;
        private String provisioningState;
        private Instant timeCreated;
        private Map<String, String> tags;
        private Map<String, Object> identity;
        private Map<String, Object> properties;

        public String getSubscriptionId() { return subscriptionId; }
        public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

        public String getResourceGroup() { return resourceGroup; }
        public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }

        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }

        public Instant getTimeCreated() { return timeCreated; }
        public void setTimeCreated(Instant timeCreated) { this.timeCreated = timeCreated; }

        public Map<String, String> getTags() { return tags; }
        public void setTags(Map<String, String> tags) { this.tags = tags; }

        public Map<String, Object> getIdentity() { return identity; }
        public void setIdentity(Map<String, Object> identity) { this.identity = identity; }

        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }

        public String armId() {
            return "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup
                    + "/providers/Microsoft.App/managedEnvironments/" + name;
        }

        public String storageKey() {
            return subscriptionId + "/" + resourceGroup + "/" + name;
        }

        public Map<String, Object> indexEntry() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", armId());
            entry.put("name", name);
            entry.put("type", "Microsoft.App/managedEnvironments");
            entry.put("location", location);
            if (tags != null && !tags.isEmpty()) {
                entry.put("tags", tags);
            }
            return entry;
        }
    }

    /** {@code Microsoft.App/containerApps}. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContainerApp {
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private String location;
        private String provisioningState;
        private Instant timeCreated;
        private Map<String, String> tags;
        private Map<String, Object> identity;
        private Map<String, Object> properties;
        /**
         * The revision suffix used to synthesize {@code latestRevisionName}. Computed once (either
         * echoed from a non-blank client-submitted {@code template.revisionSuffix}, or synthesized)
         * and kept stable across updates that don't supply their own, so
         * {@code revision_suffix}/{@code latestRevisionName} never drift between plans.
         */
        private String revisionSuffix;

        public String getSubscriptionId() { return subscriptionId; }
        public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

        public String getResourceGroup() { return resourceGroup; }
        public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }

        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }

        public Instant getTimeCreated() { return timeCreated; }
        public void setTimeCreated(Instant timeCreated) { this.timeCreated = timeCreated; }

        public Map<String, String> getTags() { return tags; }
        public void setTags(Map<String, String> tags) { this.tags = tags; }

        public Map<String, Object> getIdentity() { return identity; }
        public void setIdentity(Map<String, Object> identity) { this.identity = identity; }

        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }

        public String getRevisionSuffix() { return revisionSuffix; }
        public void setRevisionSuffix(String revisionSuffix) { this.revisionSuffix = revisionSuffix; }

        public String armId() {
            return "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup
                    + "/providers/Microsoft.App/containerApps/" + name;
        }

        public String storageKey() {
            return subscriptionId + "/" + resourceGroup + "/" + name;
        }

        public Map<String, Object> indexEntry() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", armId());
            entry.put("name", name);
            entry.put("type", "Microsoft.App/containerApps");
            entry.put("location", location);
            if (tags != null && !tags.isEmpty()) {
                entry.put("tags", tags);
            }
            return entry;
        }
    }

    /** {@code Microsoft.App/jobs}. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Job {
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private String location;
        private String provisioningState;
        private Instant timeCreated;
        private Map<String, String> tags;
        private Map<String, Object> identity;
        private Map<String, Object> properties;

        public String getSubscriptionId() { return subscriptionId; }
        public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

        public String getResourceGroup() { return resourceGroup; }
        public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }

        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }

        public Instant getTimeCreated() { return timeCreated; }
        public void setTimeCreated(Instant timeCreated) { this.timeCreated = timeCreated; }

        public Map<String, String> getTags() { return tags; }
        public void setTags(Map<String, String> tags) { this.tags = tags; }

        public Map<String, Object> getIdentity() { return identity; }
        public void setIdentity(Map<String, Object> identity) { this.identity = identity; }

        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }

        public String armId() {
            return "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup
                    + "/providers/Microsoft.App/jobs/" + name;
        }

        public String storageKey() {
            return subscriptionId + "/" + resourceGroup + "/" + name;
        }

        public Map<String, Object> indexEntry() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", armId());
            entry.put("name", name);
            entry.put("type", "Microsoft.App/jobs");
            entry.put("location", location);
            if (tags != null && !tags.isEmpty()) {
                entry.put("tags", tags);
            }
            return entry;
        }
    }

    private ContainerAppsModels() {}
}
