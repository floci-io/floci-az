package io.floci.az.compat;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.exception.ManagementException;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.containerservice.ContainerServiceManager;
import com.azure.resourcemanager.containerservice.fluent.ManagedClustersClient;
import com.azure.resourcemanager.containerservice.fluent.models.CredentialResultsInner;
import com.azure.resourcemanager.containerservice.fluent.models.ManagedClusterInner;
import com.azure.resourcemanager.containerservice.models.AgentPoolMode;
import com.azure.resourcemanager.containerservice.models.CredentialResult;
import com.azure.resourcemanager.containerservice.models.KubernetesCluster;
import com.azure.resourcemanager.containerservice.models.ManagedClusterAgentPoolProfile;
import com.azure.resourcemanager.containerservice.models.ManagedClusterIdentity;
import com.azure.resourcemanager.containerservice.models.OSType;
import com.azure.resourcemanager.containerservice.models.ResourceIdentityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility coverage for Azure Kubernetes Service through Microsoft's
 * {@code azure-resourcemanager-containerservice} management client, pointed at floci-az the same way
 * {@link ContainerAppsCompatibilityTest} is.
 *
 * <p>Written for mocked mode ({@code FLOCI_AZ_SERVICES_AKS_MOCKED=true}), where a cluster reaches
 * {@code Succeeded} without Docker. In real mode the create poller waits for the k3s container instead.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Azure Kubernetes Service Java SDK Compatibility")
class AksCompatibilityTest {

    private static final String ENDPOINT = EmulatorConfig.httpBase();
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String RESOURCE_GROUP = "aks-rg-" + SUFFIX;
    private static final String CLUSTER = "aks-" + SUFFIX;

    private static ContainerServiceManager manager;
    private static ManagedClustersClient clusters;

    @BeforeAll
    static void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        Map<String, String> endpoints = new HashMap<>(AzureEnvironment.AZURE.getEndpoints());
        endpoints.put("resourceManagerEndpointUrl", ENDPOINT + "/");
        endpoints.put("managementEndpointUrl", ENDPOINT + "/");
        AzureProfile profile = new AzureProfile(TENANT, SUBSCRIPTION, new AzureEnvironment(endpoints));
        HttpPipeline pipeline = new HttpPipelineBuilder()
                .httpClient(HttpClient.createDefault())
                .build();
        manager = ContainerServiceManager.authenticate(pipeline, profile);
        clusters = manager.serviceClient().getManagedClusters();
    }

    @Test
    @Order(1)
    void createClusterReachesSucceeded() {
        ManagedClusterAgentPoolProfile pool = new ManagedClusterAgentPoolProfile()
                .withName("nodepool1")
                .withCount(1)
                .withVmSize("Standard_DS2_v2")
                .withOsType(OSType.LINUX)
                .withMode(AgentPoolMode.SYSTEM);
        ManagedClusterInner request = new ManagedClusterInner()
                .withLocation("eastus")
                .withTags(Map.of("suite", "java-sdk"))
                .withIdentity(new ManagedClusterIdentity().withType(ResourceIdentityType.SYSTEM_ASSIGNED))
                .withDnsPrefix(CLUSTER + "-dns")
                .withAgentPoolProfiles(List.of(pool));

        ManagedClusterInner created = clusters.createOrUpdate(RESOURCE_GROUP, CLUSTER, request);

        assertEquals(CLUSTER, created.name());
        assertEquals("Succeeded", created.provisioningState());
        assertEquals("Microsoft.ContainerService/managedClusters", created.type());
        assertEquals("/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RESOURCE_GROUP
                + "/providers/Microsoft.ContainerService/managedClusters/" + CLUSTER, created.id());
        assertEquals(CLUSTER + "-dns", created.dnsPrefix());
        assertFalse(created.fqdn() == null || created.fqdn().isBlank(), "fqdn must be set");
        assertEquals(1, created.agentPoolProfiles().size());
        assertEquals("nodepool1", created.agentPoolProfiles().getFirst().name());
    }

    @Test
    @Order(2)
    void getClusterThroughTheFluentApi() {
        KubernetesCluster cluster = manager.kubernetesClusters().getByResourceGroup(RESOURCE_GROUP, CLUSTER);

        assertEquals(CLUSTER, cluster.name());
        assertEquals("eastus", cluster.regionName());
        assertEquals("java-sdk", cluster.tags().get("suite"));
        assertEquals("Succeeded", cluster.provisioningState());
        assertEquals(CLUSTER + "-dns", cluster.dnsPrefix());
        assertTrue(cluster.agentPools().containsKey("nodepool1"), "agent pools: " + cluster.agentPools().keySet());
    }

    @Test
    @Order(3)
    void listClustersByResourceGroupAndSubscription() {
        List<String> inGroup = new ArrayList<>();
        clusters.listByResourceGroup(RESOURCE_GROUP).forEach(value -> inGroup.add(value.name()));
        assertEquals(List.of(CLUSTER), inGroup);

        List<String> inSubscription = new ArrayList<>();
        clusters.list().forEach(value -> inSubscription.add(value.name()));
        assertTrue(inSubscription.contains(CLUSTER), "subscription list: " + inSubscription);
    }

    @Test
    @Order(4)
    void listAdminAndUserCredentialsReturnKubeconfigs() {
        assertKubeconfig(clusters.listClusterAdminCredentials(RESOURCE_GROUP, CLUSTER));
        assertKubeconfig(clusters.listClusterUserCredentials(RESOURCE_GROUP, CLUSTER));

        byte[] fluentKubeconfig = manager.kubernetesClusters()
                .getByResourceGroup(RESOURCE_GROUP, CLUSTER)
                .adminKubeConfigContent();
        assertTrue(new String(fluentKubeconfig, StandardCharsets.UTF_8).contains("apiVersion: v1"));
    }

    @Test
    @Order(5)
    @Disabled("floci-az answers DELETE managedClusters with a bare 202 and no Azure-AsyncOperation or Location "
            + "header, which ManagedClusters_Delete declares; the SDK poller then fails with "
            + "'Long running operation failed'")
    void deleteClusterThroughTheSdkPoller() {
        clusters.delete(RESOURCE_GROUP, CLUSTER);

        assertNotFound();
    }

    @Test
    @Order(6)
    void deleteClusterThenGetIsNotFound() throws Exception {
        // java.net.http.HttpClient is qualified: the imported HttpClient is azure-core's, used for the SDK pipeline.
        HttpResponse<Void> deleted = java.net.http.HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(ENDPOINT + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/"
                        + RESOURCE_GROUP + "/providers/Microsoft.ContainerService/managedClusters/" + CLUSTER
                        + "?api-version=2024-04-01")).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(202, deleted.statusCode());

        assertNotFound();
    }

    private static void assertNotFound() {
        ManagementException missing = assertThrows(ManagementException.class,
                () -> clusters.getByResourceGroup(RESOURCE_GROUP, CLUSTER));
        assertEquals(404, missing.getResponse().getStatusCode());
    }

    private static void assertKubeconfig(CredentialResultsInner credentials) {
        assertEquals(1, credentials.kubeconfigs().size());
        CredentialResult kubeconfig = credentials.kubeconfigs().getFirst();
        String yaml = new String(kubeconfig.value(), StandardCharsets.UTF_8);
        assertTrue(yaml.contains("apiVersion: v1"), yaml);
        assertTrue(yaml.contains("kind: Config"), yaml);
        assertTrue(yaml.contains("server: https://"), yaml);
    }
}
