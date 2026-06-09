package io.floci.az.services.acr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

public class AcrModels {

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Registry {
        private String instanceId;
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private String location;
        private Map<String, String> tags;

        private String skuName;            // Basic | Standard | Premium
        private boolean adminUserEnabled;

        // Admin credentials are returned by the management plane but, because the shared backing
        // registry runs anonymous, are not enforced at the data plane.
        private String username;           // equals the registry name when admin is enabled
        private String password;           // primary admin password
        private String password2;          // secondary admin password

        /** Path-prefixed registry endpoint: {@code localhost:{port}/{name}} (shared registry). */
        private String loginServer;

        private String provisioningState;
        private Instant createdAt;

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

        public Map<String, String> getTags() { return tags; }
        public void setTags(Map<String, String> tags) { this.tags = tags; }

        public String getSkuName() { return skuName; }
        public void setSkuName(String skuName) { this.skuName = skuName; }

        public boolean isAdminUserEnabled() { return adminUserEnabled; }
        public void setAdminUserEnabled(boolean adminUserEnabled) { this.adminUserEnabled = adminUserEnabled; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public String getPassword2() { return password2; }
        public void setPassword2(String password2) { this.password2 = password2; }

        public String getLoginServer() { return loginServer; }
        public void setLoginServer(String loginServer) { this.loginServer = loginServer; }

        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }

        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

        /** ARM resource ID for this registry. */
        public String armId() {
            return String.format(
                "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.ContainerRegistry/registries/%s",
                subscriptionId, resourceGroup, name);
        }

        /** Storage key: subscriptionId/resourceGroup/name */
        public String storageKey() {
            return subscriptionId + "/" + resourceGroup + "/" + name;
        }
    }
}
