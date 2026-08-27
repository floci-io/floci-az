package io.floci.az.services.containerapps;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class ContainerAppsModels {

    private ContainerAppsModels() {
    }

    @RegisterForReflection
    public static class ManagedEnvironmentState {
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private JsonNode document;
        private String defaultDomain;
        private Instant createdAt;

        public ManagedEnvironmentState() {
        }

        ManagedEnvironmentState(String subscriptionId, String resourceGroup, String name,
                                JsonNode document, Instant createdAt) {
            this.subscriptionId = subscriptionId;
            this.resourceGroup = resourceGroup;
            this.name = name;
            this.document = document;
            this.createdAt = createdAt;
        }

        public String getSubscriptionId() { return subscriptionId; }
        public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
        public String getResourceGroup() { return resourceGroup; }
        public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public JsonNode getDocument() { return document; }
        public void setDocument(JsonNode document) { this.document = document; }
        public String getDefaultDomain() { return defaultDomain; }
        public void setDefaultDomain(String defaultDomain) { this.defaultDomain = defaultDomain; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    }

    @RegisterForReflection
    public static class ContainerAppState {
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private JsonNode document;
        private List<RevisionState> revisions = new ArrayList<>();
        private int nextRevision = 1;
        private Instant createdAt;

        public ContainerAppState() {
        }

        ContainerAppState(String subscriptionId, String resourceGroup, String name,
                          JsonNode document, Instant createdAt) {
            this.subscriptionId = subscriptionId;
            this.resourceGroup = resourceGroup;
            this.name = name;
            this.document = document;
            this.createdAt = createdAt;
        }

        public String getSubscriptionId() { return subscriptionId; }
        public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
        public String getResourceGroup() { return resourceGroup; }
        public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public JsonNode getDocument() { return document; }
        public void setDocument(JsonNode document) { this.document = document; }
        public List<RevisionState> getRevisions() { return revisions; }
        public void setRevisions(List<RevisionState> revisions) {
            this.revisions = revisions == null ? new ArrayList<>() : revisions;
        }
        public int getNextRevision() { return nextRevision; }
        public void setNextRevision(int nextRevision) { this.nextRevision = nextRevision; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

        String storageKey() {
            return subscriptionId + "/" + resourceGroup.toLowerCase() + "/" + name.toLowerCase();
        }
    }

    @RegisterForReflection
    public static class RevisionState {
        private String name;
        private JsonNode template;
        private boolean active;
        private int replicas;
        private String provisioningState;
        private String runningState;
        private String healthState;
        private String fqdn;
        private Instant createdTime;
        private Instant lastActiveTime;

        public RevisionState() {
        }

        RevisionState(String name, JsonNode template, boolean active, int replicas, String fqdn) {
            this.name = name;
            this.template = template;
            this.active = active;
            this.replicas = replicas;
            this.fqdn = fqdn;
            this.createdTime = Instant.now();
            this.provisioningState = "Provisioned";
            this.runningState = active ? "Running" : "Stopped";
            this.healthState = active ? "Healthy" : "None";
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public JsonNode getTemplate() { return template; }
        public void setTemplate(JsonNode template) { this.template = template; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public int getReplicas() { return replicas; }
        public void setReplicas(int replicas) { this.replicas = replicas; }
        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }
        public String getRunningState() { return runningState; }
        public void setRunningState(String runningState) { this.runningState = runningState; }
        public String getHealthState() { return healthState; }
        public void setHealthState(String healthState) { this.healthState = healthState; }
        public String getFqdn() { return fqdn; }
        public void setFqdn(String fqdn) { this.fqdn = fqdn; }
        public Instant getCreatedTime() { return createdTime; }
        public void setCreatedTime(Instant createdTime) { this.createdTime = createdTime; }
        public Instant getLastActiveTime() { return lastActiveTime; }
        public void setLastActiveTime(Instant lastActiveTime) { this.lastActiveTime = lastActiveTime; }
    }
}
