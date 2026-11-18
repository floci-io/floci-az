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
import com.azure.resourcemanager.appcontainers.models.ContainerResources;
import com.azure.resourcemanager.appcontainers.models.EnvironmentVar;
import com.azure.resourcemanager.appcontainers.models.Ingress;
import com.azure.resourcemanager.appcontainers.models.Revision;
import com.azure.resourcemanager.appcontainers.models.RevisionProvisioningState;
import com.azure.resourcemanager.appcontainers.models.Scale;
import com.azure.resourcemanager.appcontainers.models.Secret;
import com.azure.resourcemanager.appcontainers.models.Template;
import com.azure.security.keyvault.secrets.SecretClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Container App secret given as a Key Vault reference ({@code keyVaultUrl}) resolves from the vault when the
 * revision starts, so an env var naming it carries the vault's value. Needs real mode
 * ({@code FLOCI_AZ_SERVICES_CONTAINER_APPS_MOCKED=false}), which the Makefile and CI set for this suite: a mocked
 * revision never starts a container, so nothing would be resolved.
 */
@DisplayName("Container Apps Key Vault reference secrets")
class ContainerAppsKeyVaultSecretCompatibilityTest {

    private static final String ENDPOINT = EmulatorConfig.httpBase();
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String RESOURCE_GROUP = "containerapps-kv-rg-" + SUFFIX;
    private static final String ENVIRONMENT = "env-kv-" + SUFFIX;
    private static final String APP = "app-kv-" + SUFFIX;
    private static final String VAULT_SECRET = "ca-token-" + SUFFIX;

    private static ContainerAppsApiManager manager;

    @BeforeAll
    static void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        Map<String, String> endpoints = new HashMap<>(AzureEnvironment.AZURE.getEndpoints());
        endpoints.put("resourceManagerEndpointUrl", ENDPOINT + "/");
        endpoints.put("managementEndpointUrl", ENDPOINT + "/");
        AzureProfile profile = new AzureProfile(TENANT, SUBSCRIPTION, new AzureEnvironment(endpoints));
        HttpPipeline pipeline = new HttpPipelineBuilder().httpClient(HttpClient.createDefault()).build();
        manager = ContainerAppsApiManager.authenticate(pipeline, profile);
    }

    @Test
    @Timeout(180)
    void anEnvVarNamingAKeyVaultReferenceSecretCarriesTheVaultValue() throws Exception {
        String value = "from-the-vault-" + SUFFIX;
        SecretClient vault = EmulatorConfig.buildKeyVaultClient();
        vault.setSecret(VAULT_SECRET, value);

        ManagedEnvironmentInner environment = manager.serviceClient().getManagedEnvironments()
                .createOrUpdate(RESOURCE_GROUP, ENVIRONMENT, new ManagedEnvironmentInner().withLocation("eastus"));
        Configuration configuration = new Configuration()
                .withActiveRevisionsMode(ActiveRevisionsMode.SINGLE)
                .withSecrets(List.of(new Secret().withName("token")
                        .withKeyVaultUrl("https://" + EmulatorConfig.ACCOUNT + ".vault.azure.net/secrets/" + VAULT_SECRET)
                        .withIdentity("System")))
                .withIngress(new Ingress().withExternal(true).withTargetPort(8080));
        // busybox serves the env var's value, so the ingress shows what the replica was started with.
        Template template = new Template()
                .withRevisionSuffix("v1")
                .withContainers(List.of(new Container()
                        .withName("echo")
                        .withImage("busybox:1.37")
                        .withCommand(List.of("sh", "-c",
                                "mkdir -p /www && printf '%s' \"$TOKEN\" > /www/index.html && exec httpd -f -p 8080 -h /www"))
                        .withEnv(List.of(new EnvironmentVar().withName("TOKEN").withSecretRef("token")))
                        .withResources(new ContainerResources().withCpu(0.25).withMemory("0.5Gi"))))
                .withScale(new Scale().withMinReplicas(1).withMaxReplicas(1));
        try {
            ContainerAppInner created = manager.serviceClient().getContainerApps().createOrUpdate(RESOURCE_GROUP, APP,
                    new ContainerAppInner().withLocation("eastus").withEnvironmentId(environment.id())
                            .withConfiguration(configuration).withTemplate(template));

            List<Revision> revisions = new ArrayList<>();
            manager.containerAppsRevisions().listRevisions(RESOURCE_GROUP, APP).forEach(revisions::add);
            assertEquals(1, revisions.size());
            assertEquals(RevisionProvisioningState.PROVISIONED, revisions.getFirst().provisioningState(),
                    "the revision fails when the Key Vault reference does not resolve");

            String fqdn = created.configuration().ingress().fqdn();
            assertFalse(fqdn == null || fqdn.isBlank(), "ingress fqdn");
            String response = getThroughIngress(fqdn);
            assertFalse(response.contains("ContainerAppMocked"),
                    "Container Apps is mocked: run this suite with FLOCI_AZ_SERVICES_CONTAINER_APPS_MOCKED=false");
            assertTrue(response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.0 200"), response);
            assertTrue(response.contains(value), response);
        } finally {
            manager.serviceClient().getContainerApps().delete(RESOURCE_GROUP, APP);
            manager.serviceClient().getManagedEnvironments().delete(RESOURCE_GROUP, ENVIRONMENT);
        }
    }

    /**
     * One plain HTTP/1.1 request to floci-az's port with the app's host name, as the docs route ingress. A socket,
     * because the JDK client refuses to set {@code Host}.
     */
    private static String getThroughIngress(String fqdn) throws IOException {
        URI base = URI.create(ENDPOINT);
        int port = base.getPort() > 0 ? base.getPort() : 80;
        try (Socket socket = new Socket(base.getHost(), port)) {
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: " + fqdn + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }
}
