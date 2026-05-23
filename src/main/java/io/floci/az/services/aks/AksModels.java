package io.floci.az.services.aks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AksModels {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ManagedCluster {
        /** Short unique ID used for Docker container naming: floci-az-aks-{instanceId}. */
        private String instanceId;
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private String location;
        private String kubernetesVersion;
        private String provisioningState;
        private String dnsPrefix;
        private String fqdn;
        /** Public API server endpoint (container-DNS resolvable inside Docker, or localhost outside). */
        private String endpoint;
        /** Internal endpoint used for readiness polling (IP-based, works on default bridge). */
        private String internalEndpoint;
        private String containerId;
        /** Base64-encoded kubeconfig extracted from k3s (or a mock kubeconfig). */
        private String kubeconfig;
        /** Base64-encoded certificate authority data from the cluster. */
        private String caData;
        private Instant createdAt;
        private Map<String, String> tags;
        private List<AgentPoolProfile> agentPoolProfiles;

        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

        public String getSubscriptionId() { return subscriptionId; }
        public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

        public String getResourceGroup() { return resourceGroup; }
        public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }

        public String getKubernetesVersion() { return kubernetesVersion; }
        public void setKubernetesVersion(String kubernetesVersion) { this.kubernetesVersion = kubernetesVersion; }

        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }

        public String getDnsPrefix() { return dnsPrefix; }
        public void setDnsPrefix(String dnsPrefix) { this.dnsPrefix = dnsPrefix; }

        public String getFqdn() { return fqdn; }
        public void setFqdn(String fqdn) { this.fqdn = fqdn; }

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public String getInternalEndpoint() { return internalEndpoint; }
        public void setInternalEndpoint(String internalEndpoint) { this.internalEndpoint = internalEndpoint; }

        public String getContainerId() { return containerId; }
        public void setContainerId(String containerId) { this.containerId = containerId; }

        public String getKubeconfig() { return kubeconfig; }
        public void setKubeconfig(String kubeconfig) { this.kubeconfig = kubeconfig; }

        public String getCaData() { return caData; }
        public void setCaData(String caData) { this.caData = caData; }

        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

        public Map<String, String> getTags() { return tags; }
        public void setTags(Map<String, String> tags) { this.tags = tags; }

        public List<AgentPoolProfile> getAgentPoolProfiles() { return agentPoolProfiles; }
        public void setAgentPoolProfiles(List<AgentPoolProfile> agentPoolProfiles) {
            this.agentPoolProfiles = agentPoolProfiles;
        }

        /** ARM resource ID for this cluster. */
        public String armId() {
            return String.format(
                "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.ContainerService/managedClusters/%s",
                subscriptionId, resourceGroup, name);
        }

        /** Storage key: subscriptionId/resourceGroup/name */
        public String storageKey() {
            return subscriptionId + "/" + resourceGroup + "/" + name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AgentPoolProfile {
        private String name;
        private int count;
        private String vmSize;
        private String osType;
        private String mode;
        private String provisioningState;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }

        public String getVmSize() { return vmSize; }
        public void setVmSize(String vmSize) { this.vmSize = vmSize; }

        public String getOsType() { return osType; }
        public void setOsType(String osType) { this.osType = osType; }

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }

        public static AgentPoolProfile defaultPool() {
            AgentPoolProfile p = new AgentPoolProfile();
            p.name = "nodepool1";
            p.count = 1;
            p.vmSize = "Standard_DS2_v2";
            p.osType = "Linux";
            p.mode = "System";
            p.provisioningState = "Succeeded";
            return p;
        }
    }
}
