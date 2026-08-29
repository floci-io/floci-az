package io.floci.az.services.containerapps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.StoredObject;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.containerapps.ContainerAppsModels.ContainerAppState;
import io.floci.az.services.containerapps.ContainerAppsModels.RevisionState;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerAppsHandlerUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROVIDER = "subscriptions/sub/resourceGroups/rg/providers/Microsoft.App/";
    private static final String ENVIRONMENT_ID = "/subscriptions/sub/resourceGroups/rg/providers/"
            + "Microsoft.App/managedEnvironments/env";

    private ContainerAppRuntimeManager runtimeManager;
    private ContainerAppIngressProxy ingressProxy;
    private ContainerAppsHandler handler;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().containerApps().dnsSuffix()).thenReturn("azurecontainerapps.io");
        when(config.services().containerApps().mocked()).thenReturn(false);

        runtimeManager = mock(ContainerAppRuntimeManager.class);
        ingressProxy = mock(ContainerAppIngressProxy.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        StorageBackend<String, StoredObject> storage = new InMemoryStorage<>();
        when(storageFactory.create("containerapps")).thenReturn(storage);
        handler = new ContainerAppsHandler(config, runtimeManager, ingressProxy, storageFactory);
    }

    @Test
    void failedSingleModeReplacementLeavesReadyRevisionActive() {
        doNothing().doThrow(new IllegalStateException("readiness failed"))
                .when(runtimeManager).startRevision(any(), any(), any(), anyInt(), anyInt());
        createEnvironment("sub", "rg", "env");

        assertEquals(201, handler.handle(request("PUT", PROVIDER + "containerApps/app", appBody("v1")))
                .getStatus());
        Response update = handler.handle(request("PATCH", PROVIDER + "containerApps/app", appBody("v2")));

        assertEquals(200, update.getStatus());
        ObjectNode app = (ObjectNode) update.getEntity();
        assertEquals("app--v1", app.path("properties").path("latestReadyRevisionName").asText());
        assertEquals("Failed", app.path("properties").path("provisioningState").asText());

        Response revisionsResponse = handler.handle(request("GET",
                PROVIDER + "containerApps/app/revisions", null));
        @SuppressWarnings("unchecked")
        List<ObjectNode> revisions = (List<ObjectNode>) ((Map<String, ?>) revisionsResponse.getEntity()).get("value");
        assertTrue(revisions.get(0).path("properties").path("active").asBoolean());
        assertFalse(revisions.get(1).path("properties").path("active").asBoolean());
        verify(runtimeManager, never()).stopRevision(any(), org.mockito.ArgumentMatchers.eq("app--v1"));
    }

    @Test
    void trafficWeightsSelectRevisionsProportionally() throws Exception {
        ObjectNode document = (ObjectNode) MAPPER.readTree("""
                {"properties":{"configuration":{"ingress":{"traffic":[
                  {"revisionName":"app--blue","weight":20},
                  {"revisionName":"app--green","weight":80}
                ]}}}}
                """);
        ContainerAppState app = new ContainerAppState("sub", "rg", "app", document, Instant.now());
        RevisionState blue = revision("app--blue", Instant.parse("2026-01-01T00:00:00Z"));
        RevisionState green = revision("app--green", Instant.parse("2026-01-01T00:00:01Z"));
        app.getRevisions().addAll(List.of(blue, green));

        int blueSelections = 0;
        for (int request = 0; request < 100; request++) {
            if (handler.routeRevision(app, document.path("properties").path("configuration").path("ingress"))
                    .orElseThrow() == blue) {
                blueSelections++;
            }
        }

        assertEquals(20, blueSelections);
    }

    @Test
    void internalIngressUsesExactManagedContainerAddresses() {
        List<String> addresses = List.of("172.18.0.4", "fd00::4");
        assertFalse(ContainerLifecycleManager.matchesAnyAddress(null, addresses));
        assertFalse(ContainerLifecycleManager.matchesAnyAddress("172.18.0.5", addresses));
        assertFalse(ContainerLifecycleManager.matchesAnyAddress("fd00::5", addresses));
        assertTrue(ContainerLifecycleManager.matchesAnyAddress("172.18.0.4", addresses));
        assertTrue(ContainerLifecycleManager.matchesAnyAddress("fd00:0:0:0:0:0:0:4", addresses));
    }

    @Test
    void restoredInternalIngressAuthorizesCallerBeforeStartingRuntime() {
        AtomicBoolean runtimeStarted = new AtomicBoolean();
        doAnswer(ignored -> {
            runtimeStarted.set(true);
            return null;
        }).when(runtimeManager).startRevision(any(), any(), any(), anyInt(), anyInt());
        when(runtimeManager.isInternalCaller("172.18.0.4")).thenReturn(true);
        var endpoint = new ContainerLifecycleManager.EndpointInfo("localhost", 8080);
        when(runtimeManager.endpoint(any(), any()))
                .thenAnswer(ignored -> runtimeStarted.get() ? Optional.of(endpoint) : Optional.empty());
        when(ingressProxy.proxy(any(), any())).thenReturn(Response.noContent().build());

        ObjectNode environment = (ObjectNode) createEnvironment("sub", "rg", "env").getEntity();
        String internalBody = appBody("v1").replace("\"external\":true", "\"external\":false");
        assertEquals(201, handler.handle(request("PUT", PROVIDER + "containerApps/internal-app", internalBody))
                .getStatus());
        runtimeStarted.set(false);

        Response response = handler.handle(new AzureRequest("GET", ingressAccountName(environment, "internal-app"),
                "containerapps", "hello",
                null, null, Map.of(), null, false, "172.18.0.4"));

        assertEquals(204, response.getStatus());
        assertTrue(runtimeStarted.get());
    }

    @Test
    void restoredInternalIngressRejectsCallerWithoutStartingRuntime() {
        ObjectNode environment = (ObjectNode) createEnvironment("sub", "rg", "env").getEntity();
        String internalBody = appBody("v1").replace("\"external\":true", "\"external\":false");
        assertEquals(201, handler.handle(request("PUT", PROVIDER + "containerApps/internal-app", internalBody))
                .getStatus());
        clearInvocations(runtimeManager);

        Response response = handler.handle(new AzureRequest("GET", ingressAccountName(environment, "internal-app"),
                "containerapps", "hello",
                null, null, Map.of(), null, false, "203.0.113.4"));

        assertEquals(404, response.getStatus());
        verify(runtimeManager, never()).startRevision(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void defaultDomainsRemainStableAndUniqueAcrossResourceGroups() {
        ObjectNode first = (ObjectNode) createEnvironment("sub", "rg-a", "shared").getEntity();
        ObjectNode second = (ObjectNode) createEnvironment("sub", "rg-b", "shared").getEntity();
        String firstDomain = first.path("properties").path("defaultDomain").asText();
        String secondDomain = second.path("properties").path("defaultDomain").asText();

        assertNotEquals(firstDomain, secondDomain);
        ObjectNode persisted = (ObjectNode) handler.handle(request("GET",
                "subscriptions/sub/resourceGroups/rg-a/providers/Microsoft.App/managedEnvironments/shared", null))
                .getEntity();
        assertEquals(firstDomain, persisted.path("properties").path("defaultDomain").asText());
    }

    private Response createEnvironment(String subscription, String resourceGroup, String name) {
        return handler.handle(request("PUT", "subscriptions/" + subscription + "/resourceGroups/"
                + resourceGroup + "/providers/Microsoft.App/managedEnvironments/" + name,
                "{\"location\":\"eastus\",\"properties\":{}}"));
    }

    private static String ingressAccountName(ObjectNode environment, String appName) {
        String fqdn = appName + "." + environment.path("properties").path("defaultDomain").asText();
        return fqdn.substring(0, fqdn.length() - ".azurecontainerapps.io".length());
    }

    private static RevisionState revision(String name, Instant createdTime) throws Exception {
        RevisionState revision = new RevisionState(name, MAPPER.readTree("{}"), true, 1, name + ".example");
        revision.setCreatedTime(createdTime);
        return revision;
    }

    private static AzureRequest request(String method, String path, String body) {
        return new AzureRequest(method, "containerapps", "containerapps", path, null,
                body == null ? null : new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                Map.of(), null, false);
    }

    private static String appBody(String suffix) {
        return """
                {"location":"eastus","properties":{
                  "environmentId":"%s",
                  "configuration":{"activeRevisionsMode":"Single","ingress":{"external":true,"targetPort":8080}},
                  "template":{"revisionSuffix":"%s","containers":[{"name":"web","image":"nginx:alpine"}],
                    "scale":{"minReplicas":1,"maxReplicas":2}}
                }}
                """.formatted(ENVIRONMENT_ID, suffix);
    }
}
