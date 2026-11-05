package io.floci.az.services.vm;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.StoredObject;
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
        return new AzureRequest(method, "", "vm", path, new SyntheticHttpHeaders(headers), body,
                Map.of(), Map.of(), null, secure, null, null, path, null, authority);
    }

    private static void assertPollingUrl(Response response, String expectedOrigin, String location) {
        assertEquals(202, response.getStatus());
        String async = response.getHeaderString("Azure-AsyncOperation");
        assertNotNull(async);
        assertEquals(async, response.getHeaderString("Location"));
        URI url = URI.create(async);
        assertEquals(expectedOrigin, url.getScheme() + "://" + url.getRawAuthority());
        String operationPath = "/subscriptions/url-sub/providers/Microsoft.Compute/locations/" + location + "/operations/";
        assertTrue(url.getPath().startsWith(operationPath));
        UUID.fromString(url.getPath().substring(operationPath.length()));
        assertEquals("api-version=2024-11-01&monitor=true", url.getRawQuery());
    }
}
