package io.floci.az.services.vm;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.SyntheticHttpHeaders;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.vm.VmModels.VirtualMachine;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VmPollingUrlsTest {

    private static final String VM_PATH = "subscriptions/url-sub/resourceGroups/url-rg"
            + "/providers/Microsoft.Compute/virtualMachines/url-vm";
    private static final String CONFIGURED = "http://configured.example:4577";
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final VmContainerManager containers = mock(VmContainerManager.class);
    private VmHandler handler;

    @BeforeEach
    void setUp() {
        when(config.effectiveBaseUrl()).thenReturn(CONFIGURED);
        when(config.services().vm().mocked()).thenReturn(true);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create("vm")).thenReturn(new InMemoryStorage<String, StoredObject>());
        handler = new VmHandler(config, containers, factory, CloudDefaults.catalogOnly());
    }

    @AfterEach
    void tearDown() {
        handler.shutdown();
    }

    static Stream<Arguments> origins() {
        return Stream.of(
                Arguments.of(true, "tls.example:8443", Map.of(), "https://tls.example:8443"),
                Arguments.of(false, "custom.example:8123", Map.of(), "http://custom.example:8123"),
                Arguments.of(false, "proxy.example:9443", Map.of("X-Forwarded-Proto", "HTTPS, http"),
                        "https://proxy.example:9443"),
                Arguments.of(true, "http2.example:8443", Map.of("Host", "stale.example:1"),
                        "https://http2.example:8443"),
                Arguments.of(false, null, Map.of("Host", "host.example:8080"), "http://host.example:8080"),
                Arguments.of(true, null, Map.of("X-Forwarded-Proto", "https"), CONFIGURED),
                Arguments.of(true, "secure.example:8443", Map.of("X-Forwarded-Proto", "invalid"),
                        "https://secure.example:8443"),
                Arguments.of(false, "plain.example:8080", Map.of("X-Forwarded-Proto", "ftp, https"),
                        "http://plain.example:8080"),
                Arguments.of(false, " ", Map.of("Host", " "), CONFIGURED));
    }

    @ParameterizedTest
    @MethodSource("origins")
    void pollingHeadersPreserveTheRequestOrigin(boolean secure, String authority, Map<String, String> headers,
                                               String expectedOrigin) {
        createVm("{\"location\":\"westus\"}");
        Response response = handler.handle(request("POST", VM_PATH + "/restart", secure, authority, headers,
                InputStream.nullInputStream()));
        assertPollingUrl(response, expectedOrigin, "westus");
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "restart", "powerOff", "deallocate", "redeploy", "reapply"})
    void everyPowerActionCarriesTheRequestOrigin(String action) {
        createVm("{\"location\":\"westus\"}");
        assertPollingUrl(power(action), "https://caller.example:8443", "westus");
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "restart", "powerOff", "deallocate", "redeploy", "reapply"})
    void failedVmWithoutAContainerCarriesTheRequestOrigin(String action) {
        when(config.services().vm().mocked()).thenReturn(false);
        doThrow(new IllegalStateException("image unavailable")).when(containers).startVm(any(VirtualMachine.class));
        createVm("{\"location\":\"westus\"}");
        assertPollingUrl(power(action), "https://caller.example:8443", "westus");
        assertEquals("Failed", properties().get("provisioningState"));
    }

    @Test
    void containerPowerFailureStillReturnsTheRequestOrigin() {
        createVm("{\"location\":\"westus\"}");
        when(config.services().vm().mocked()).thenReturn(false);
        doThrow(new IllegalStateException("restart failed")).when(containers).restartContainer(any(VirtualMachine.class));
        assertPollingUrl(power("restart"), "https://caller.example:8443", "westus");
        assertEquals("Succeeded", properties().get("provisioningState"));
    }

    @Test
    void aPowerActionThatSucceededPollsSucceededOnBothUrls() {
        createVm("{\"location\":\"westus\"}");
        Response action = power("powerOff");

        Response async = poll(action, "Azure-AsyncOperation");
        assertEquals(200, async.getStatus());
        assertEquals("Succeeded", body(async).get("status"));
        assertNotNull(body(async).get("startTime"));
        assertNotNull(body(async).get("endTime"));
        assertNull(body(async).get("error"));

        Response monitor = poll(action, "Location");
        assertEquals(200, monitor.getStatus());
        assertNull(monitor.getEntity());
    }

    @Test
    void anOperationThatWasNeverIssuedIsNotFound() {
        Response response = handler.handle(request("GET", "subscriptions/url-sub/providers/Microsoft.Compute"
                + "/locations/westus/operations/" + UUID.randomUUID(), Map.of("api-version", "2024-11-01"),
                false, null, Map.of(), InputStream.nullInputStream()));
        assertEquals(404, response.getStatus());
        assertEquals("ResourceNotFound", ((Map<?, ?>) body(response).get("error")).get("code"));
    }

    @Test
    void aContainerActionThatFailsFailsTheOperationAndLeavesThePowerState() {
        createVm("{\"location\":\"westus\"}");
        when(config.services().vm().mocked()).thenReturn(false);
        doThrow(new IllegalStateException("stop failed")).when(containers).stopContainer(any(VirtualMachine.class));
        Response action = power("powerOff");
        assertEquals(202, action.getStatus());

        Response async = poll(action, "Azure-AsyncOperation");
        assertEquals(200, async.getStatus());
        assertEquals("Failed", body(async).get("status"));
        Map<?, ?> error = (Map<?, ?>) body(async).get("error");
        assertEquals("InternalServerError", error.get("code"));
        assertTrue(error.get("message").toString().contains("stop failed"));

        Response monitor = poll(action, "Location");
        assertEquals(500, monitor.getStatus());
        assertEquals("InternalServerError", ((Map<?, ?>) body(monitor).get("error")).get("code"));

        assertEquals("Succeeded", properties().get("provisioningState"));
        Response instanceView = handler.handle(request("GET", VM_PATH + "/instanceView", false, null, Map.of(),
                InputStream.nullInputStream()));
        assertTrue(instanceView.getEntity().toString().contains("PowerState/running"));
    }

    @Test
    void startingAFailedVmThatStillCannotStartFailsTheOperation() {
        when(config.services().vm().mocked()).thenReturn(false);
        doThrow(new IllegalStateException("image unavailable")).when(containers).startVm(any(VirtualMachine.class));
        createVm("{\"location\":\"westus\"}");

        Response action = power("start");
        assertEquals("Failed", body(poll(action, "Azure-AsyncOperation")).get("status"));
        assertEquals(500, poll(action, "Location").getStatus());
    }

    @Test
    void startingAFailedVmFollowsTheRetriedProvisioning() {
        when(config.services().vm().mocked()).thenReturn(false);
        doThrow(new IllegalStateException("image unavailable")).when(containers).startVm(any(VirtualMachine.class));
        createVm("{\"location\":\"westus\"}");
        doNothing().when(containers).startVm(any(VirtualMachine.class));

        Response action = power("start");
        assertEquals("Creating", properties().get("provisioningState"));
        assertEquals("InProgress", body(poll(action, "Azure-AsyncOperation")).get("status"));
        Response monitor = poll(action, "Location");
        assertEquals(202, monitor.getStatus());
        assertEquals(action.getHeaderString("Location"), monitor.getHeaderString("Location"));

        when(containers.isRunning(any(VirtualMachine.class))).thenReturn(true);
        handler.markSucceededOnceRunning(vmArmId());

        Response settled = poll(action, "Azure-AsyncOperation");
        assertEquals("Succeeded", body(settled).get("status"));
        assertNotNull(body(settled).get("endTime"));
        assertEquals(200, poll(action, "Location").getStatus());
    }

    @Test
    void aResetForgetsIssuedOperations() {
        createVm("{\"location\":\"westus\"}");
        Response action = power("restart");
        handler.clear();
        assertEquals(404, poll(action, "Azure-AsyncOperation").getStatus());
    }

    @Test
    void blankLocationRetainsTheDefaultPollingLocation() {
        createVm("{\"location\":\" \"}");
        assertPollingUrl(power("start"), "https://caller.example:8443", "eastus");
    }

    @Test
    void missingVmStillReturnsNotFoundWithoutPollingHeaders() {
        Response response = power("start");
        assertEquals(404, response.getStatus());
        assertNull(response.getHeaderString("Azure-AsyncOperation"));
        assertNull(response.getHeaderString("Location"));
    }

    private void createVm(String body) {
        Response response = handler.handle(request("PUT", VM_PATH, false, null, Map.of(),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
        assertEquals(201, response.getStatus());
    }

    private Response power(String action) {
        return handler.handle(request("POST", VM_PATH + "/" + action, true, "caller.example:8443", Map.of(),
                InputStream.nullInputStream()));
    }

    private Map<?, ?> properties() {
        Response response = handler.handle(request("GET", VM_PATH, false, null, Map.of(), InputStream.nullInputStream()));
        assertEquals(200, response.getStatus());
        return (Map<?, ?>) ((Map<?, ?>) response.getEntity()).get("properties");
    }

    private static AzureRequest request(String method, String path, boolean secure, String authority,
                                        Map<String, String> headers, InputStream body) {
        return request(method, path, Map.of(), secure, authority, headers, body);
    }

    private static AzureRequest request(String method, String path, Map<String, String> query, boolean secure,
                                        String authority, Map<String, String> headers, InputStream body) {
        return new AzureRequest(method, "", "vm", path, new SyntheticHttpHeaders(headers), body,
                query, Map.of(), null, secure, null, null, path, null, authority);
    }

    /** GETs the operation behind a power action's polling header, the way a client follows it. */
    private Response poll(Response action, String header) {
        URI url = URI.create(action.getHeaderString(header));
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : url.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            query.put(kv[0], kv.length > 1 ? kv[1] : "");
        }
        return handler.handle(request("GET", url.getPath().substring(1), query, true, "caller.example:8443",
                Map.of(), InputStream.nullInputStream()));
    }

    private static ArmResourceId vmArmId() {
        return ArmResourceId.parse("/" + VM_PATH).orElseThrow();
    }

    private static Map<?, ?> body(Response response) {
        return (Map<?, ?>) response.getEntity();
    }

    private static void assertPollingUrl(Response response, String expectedOrigin, String location) {
        assertEquals(202, response.getStatus());
        String async = response.getHeaderString("Azure-AsyncOperation");
        assertNotNull(async);
        assertEquals(async + "&monitor=true", response.getHeaderString("Location"));
        assertEquals("1", response.getHeaderString("Retry-After"));
        URI url = URI.create(async);
        assertEquals(expectedOrigin, url.getScheme() + "://" + url.getRawAuthority());
        String operationPath = "/subscriptions/url-sub/providers/Microsoft.Compute/locations/" + location + "/operations/";
        assertTrue(url.getPath().startsWith(operationPath));
        UUID.fromString(url.getPath().substring(operationPath.length()));
        assertEquals("api-version=2024-11-01", url.getRawQuery());
    }
}
