package io.floci.az.compat;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.appcontainers.ContainerAppsApiManager;
import com.azure.resourcemanager.appcontainers.fluent.models.ContainerAppInner;
import com.azure.resourcemanager.appcontainers.fluent.models.ManagedEnvironmentInner;
import com.azure.resourcemanager.appcontainers.models.ActiveRevisionsMode;
import com.azure.resourcemanager.appcontainers.models.Configuration;
import com.azure.resourcemanager.appcontainers.models.Container;
import com.azure.resourcemanager.appcontainers.models.ContainerApp;
import com.azure.resourcemanager.appcontainers.models.ContainerAppProvisioningState;
import com.azure.resourcemanager.appcontainers.models.ContainerResources;
import com.azure.resourcemanager.appcontainers.models.EnvironmentProvisioningState;
import com.azure.resourcemanager.appcontainers.models.EnvironmentVar;
import com.azure.resourcemanager.appcontainers.models.Ingress;
import com.azure.resourcemanager.appcontainers.models.ManagedEnvironment;
import com.azure.resourcemanager.appcontainers.models.Revision;
import com.azure.resourcemanager.appcontainers.models.Scale;
import com.azure.resourcemanager.appcontainers.models.Secret;
import com.azure.resourcemanager.appcontainers.models.Template;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compatibility coverage using Microsoft's generated Container Apps management client. */
@DisplayName("Azure Container Apps Java SDK Compatibility")
class ContainerAppsCompatibilityTest {

    private static final String ENDPOINT = EmulatorConfig.httpBase();
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String RESOURCE_GROUP = "containerapps-rg-" + suffix();
    private static final String ENVIRONMENT = "env-" + suffix();
    private static final String APP = "app-" + suffix();

    private static ContainerAppsApiManager manager;

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
        manager = ContainerAppsApiManager.authenticate(pipeline, profile);
    }

    @Test
    void fullLifecycleThroughOfficialSdk() {
        ManagedEnvironmentInner environmentRequest = new ManagedEnvironmentInner()
                .withLocation("eastus")
                .withTags(Map.of("suite", "java-sdk"));
        ManagedEnvironmentInner environment = manager.serviceClient().getManagedEnvironments()
                .createOrUpdate(RESOURCE_GROUP, ENVIRONMENT, environmentRequest);

        assertEquals(EnvironmentProvisioningState.SUCCEEDED, environment.provisioningState());
        assertTrue(environment.defaultDomain().endsWith(".azurecontainerapps.io"));

        String environmentId = environment.id();
        Configuration configuration = new Configuration()
                .withActiveRevisionsMode(ActiveRevisionsMode.SINGLE)
                .withSecrets(List.of(new Secret().withName("message").withValue("hello")))
                .withIngress(new Ingress().withExternal(true).withTargetPort(80));
        Template template = new Template()
                .withRevisionSuffix("v1")
                .withContainers(List.of(new Container()
                        .withName("web")
                        .withImage("nginx:alpine")
                        .withEnv(List.of(new EnvironmentVar().withName("MESSAGE").withSecretRef("message")))
                        .withResources(new ContainerResources().withCpu(0.25).withMemory("0.5Gi"))))
                .withScale(new Scale().withMinReplicas(1).withMaxReplicas(2));
        ContainerAppInner appRequest = new ContainerAppInner()
                .withLocation("eastus")
                .withEnvironmentId(environmentId)
                .withConfiguration(configuration)
                .withTemplate(template)
                .withTags(Map.of("suite", "java-sdk"));

        ContainerAppInner created = manager.serviceClient().getContainerApps()
                .createOrUpdate(RESOURCE_GROUP, APP, appRequest);
        assertEquals(ContainerAppProvisioningState.SUCCEEDED, created.provisioningState());
        assertEquals(APP + "--v1", created.latestRevisionName());
        assertEquals("message", manager.containerApps().listSecrets(RESOURCE_GROUP, APP)
                .value().getFirst().name());

        ContainerApp fetched = manager.containerApps().getByResourceGroup(RESOURCE_GROUP, APP);
        assertEquals(APP, fetched.name());
        assertEquals(environmentId, fetched.environmentId());
        assertEquals(1, fetched.template().scale().minReplicas());
        assertEquals("java-sdk", fetched.tags().get("suite"));

        List<Revision> revisions = new ArrayList<>();
        manager.containerAppsRevisions().listRevisions(RESOURCE_GROUP, APP).forEach(revisions::add);
        assertEquals(1, revisions.size());
        assertEquals(APP + "--v1", revisions.getFirst().name());
        assertEquals(1, revisions.getFirst().replicas());
        assertTrue(revisions.getFirst().active());

        List<ContainerApp> apps = new ArrayList<>();
        manager.containerApps().listByResourceGroup(RESOURCE_GROUP).forEach(apps::add);
        assertTrue(apps.stream().anyMatch(app -> APP.equals(app.name())));
        List<ManagedEnvironment> environments = new ArrayList<>();
        manager.managedEnvironments().listByResourceGroup(RESOURCE_GROUP).forEach(environments::add);
        assertTrue(environments.stream().anyMatch(value -> ENVIRONMENT.equals(value.name())));

        manager.serviceClient().getContainerApps().delete(RESOURCE_GROUP, APP);
        manager.serviceClient().getManagedEnvironments().delete(RESOURCE_GROUP, ENVIRONMENT);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
