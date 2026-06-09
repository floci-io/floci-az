package io.floci.az.services.vm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

public class VmModels {

    /**
     * Persisted Azure Virtual Machine (Microsoft.Compute/virtualMachines).
     *
     * <p>The submitted {@code properties} block (hardwareProfile, storageProfile, osProfile,
     * networkProfile, …) is stored verbatim so GET round-trips faithfully for SDKs and Terraform.
     * {@code provisioningState}, {@code vmId} and power state are managed by the emulator.</p>
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VirtualMachine {
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private String location;
        private String vmId;
        private String provisioningState;
        /** Power state code: running | stopped | deallocated | starting | stopping | deallocating. */
        private String powerState;
        private Instant timeCreated;
        private Map<String, String> tags;
        private Map<String, Object> properties;
        /** Docker container id when running in non-mocked mode (phase 2). */
        private String containerId;

        public String getSubscriptionId() { return subscriptionId; }
        public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

        public String getResourceGroup() { return resourceGroup; }
        public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }

        public String getVmId() { return vmId; }
        public void setVmId(String vmId) { this.vmId = vmId; }

        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }

        public String getPowerState() { return powerState; }
        public void setPowerState(String powerState) { this.powerState = powerState; }

        public Instant getTimeCreated() { return timeCreated; }
        public void setTimeCreated(Instant timeCreated) { this.timeCreated = timeCreated; }

        public Map<String, String> getTags() { return tags; }
        public void setTags(Map<String, String> tags) { this.tags = tags; }

        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }

        public String getContainerId() { return containerId; }
        public void setContainerId(String containerId) { this.containerId = containerId; }

        /** ARM resource ID for this virtual machine. */
        public String armId() {
            return "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup
                    + "/providers/Microsoft.Compute/virtualMachines/" + name;
        }

        /** Storage key: subscriptionId/resourceGroup/name. */
        public String storageKey() {
            return subscriptionId + "/" + resourceGroup + "/" + name;
        }
    }

    /**
     * Azure VM power-state codes, surfaced in {@code instanceView.statuses} as
     * {@code PowerState/<code>}.
     */
    public enum PowerState {
        RUNNING("running", "VM running"),
        STOPPED("stopped", "VM stopped"),
        DEALLOCATED("deallocated", "VM deallocated"),
        STARTING("starting", "VM starting"),
        STOPPING("stopping", "VM stopping"),
        DEALLOCATING("deallocating", "VM deallocating");

        private final String code;
        private final String displayStatus;

        PowerState(String code, String displayStatus) {
            this.code = code;
            this.displayStatus = displayStatus;
        }

        public String code() { return code; }
        public String displayStatus() { return displayStatus; }
        public String statusCode() { return "PowerState/" + code; }

        public static PowerState fromCode(String code) {
            if (code != null) {
                for (PowerState p : values()) {
                    if (p.code.equals(code)) { return p; }
                }
            }
            return RUNNING;
        }
    }

    private VmModels() {}
}
