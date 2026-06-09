package io.floci.az.services.redis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

public class RedisModels {

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RedisCache {
        /** Short unique ID used for Docker container naming: floci-az-redis-{instanceId}. */
        private String instanceId;
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private String location;
        private Map<String, String> tags;

        private String skuName;
        private String skuFamily;
        private int skuCapacity;
        private String redisVersion;
        private boolean enableNonSslPort;
        private String minimumTlsVersion;
        private Map<String, String> redisConfiguration;

        private String primaryKey;
        private String secondaryKey;

        /** Host clients connect to: localhost (native) or the container name (in-Docker). */
        private String hostName;
        /** Non-SSL host port mapped to the container's 6379. */
        private int port;
        /** SSL host port (reserved; non-SSL is the supported data-plane for now). */
        private int sslPort;

        private String containerId;
        /** IP-based endpoint used for the readiness PING probe on the default bridge. */
        private String internalEndpoint;

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

        public String getSkuFamily() { return skuFamily; }
        public void setSkuFamily(String skuFamily) { this.skuFamily = skuFamily; }

        public int getSkuCapacity() { return skuCapacity; }
        public void setSkuCapacity(int skuCapacity) { this.skuCapacity = skuCapacity; }

        public String getRedisVersion() { return redisVersion; }
        public void setRedisVersion(String redisVersion) { this.redisVersion = redisVersion; }

        public boolean isEnableNonSslPort() { return enableNonSslPort; }
        public void setEnableNonSslPort(boolean enableNonSslPort) { this.enableNonSslPort = enableNonSslPort; }

        public String getMinimumTlsVersion() { return minimumTlsVersion; }
        public void setMinimumTlsVersion(String minimumTlsVersion) { this.minimumTlsVersion = minimumTlsVersion; }

        public Map<String, String> getRedisConfiguration() { return redisConfiguration; }
        public void setRedisConfiguration(Map<String, String> redisConfiguration) {
            this.redisConfiguration = redisConfiguration;
        }

        public String getPrimaryKey() { return primaryKey; }
        public void setPrimaryKey(String primaryKey) { this.primaryKey = primaryKey; }

        public String getSecondaryKey() { return secondaryKey; }
        public void setSecondaryKey(String secondaryKey) { this.secondaryKey = secondaryKey; }

        public String getHostName() { return hostName; }
        public void setHostName(String hostName) { this.hostName = hostName; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public int getSslPort() { return sslPort; }
        public void setSslPort(int sslPort) { this.sslPort = sslPort; }

        public String getContainerId() { return containerId; }
        public void setContainerId(String containerId) { this.containerId = containerId; }

        public String getInternalEndpoint() { return internalEndpoint; }
        public void setInternalEndpoint(String internalEndpoint) { this.internalEndpoint = internalEndpoint; }

        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }

        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

        /** ARM resource ID for this cache. */
        public String armId() {
            return String.format(
                "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Cache/Redis/%s",
                subscriptionId, resourceGroup, name);
        }

        /** Storage key: subscriptionId/resourceGroup/name */
        public String storageKey() {
            return subscriptionId + "/" + resourceGroup + "/" + name;
        }
    }
}
