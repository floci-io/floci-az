package io.floci.az.services.aci;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AciModels {

    /**
     * Persisted Azure Container Group (Microsoft.ContainerInstance/containerGroups).
     *
     * <p>The submitted {@code properties} block (containers, ipAddress, volumes, diagnostics, …)
     * is stored verbatim — including secure environment values and registry passwords, which are
     * needed to launch real containers — so GET round-trips faithfully for SDKs and Terraform.
     * Secrets are stripped by the handler's response builder, never here.
     * {@code provisioningState} and container runtime state are managed by the emulator.</p>
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContainerGroup {
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private String location;
        private String provisioningState;
        private Instant timeCreated;
        private Map<String, String> tags;
        private Map<String, Object> properties;
        /** container name → Docker container id, populated in non-mocked mode (PR 2). */
        private Map<String, String> containerIds;
        /** Host ports allocated for the group's published ports (PR 2). */
        private List<Integer> allocatedHostPorts;

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

        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }

        public Map<String, String> getContainerIds() { return containerIds; }
        public void setContainerIds(Map<String, String> containerIds) { this.containerIds = containerIds; }

        public List<Integer> getAllocatedHostPorts() { return allocatedHostPorts; }
        public void setAllocatedHostPorts(List<Integer> allocatedHostPorts) { this.allocatedHostPorts = allocatedHostPorts; }

        /** ARM resource ID for this container group. */
        public String armId() {
            return "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup
                    + "/providers/Microsoft.ContainerInstance/containerGroups/" + name;
        }

        /** Storage key: subscriptionId/resourceGroup/name. */
        public String storageKey() {
            return subscriptionId + "/" + resourceGroup + "/" + name;
        }

        /** Minimal ARM resource map for the resource-group {@code /resources} index. */
        public Map<String, Object> indexEntry() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", armId());
            entry.put("name", name);
            entry.put("type", "Microsoft.ContainerInstance/containerGroups");
            entry.put("location", location);
            if (tags != null && !tags.isEmpty()) {
                entry.put("tags", tags);
            }
            return entry;
        }
    }

    private AciModels() {}
}
