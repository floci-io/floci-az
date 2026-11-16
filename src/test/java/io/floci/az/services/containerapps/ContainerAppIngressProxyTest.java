package io.floci.az.services.containerapps;

import com.sun.net.httpserver.HttpServer;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.docker.ContainerLifecycleManager;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerAppIngressProxyTest {

    @Test
    void preservesEncodedIngressPath() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().getRawPath());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        try {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().containerApps().ingressTimeoutSeconds()).thenReturn(5);
            HttpHeaders headers = mock(HttpHeaders.class);
            when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
            AzureRequest request = new AzureRequest("GET", "app", "containerapps", "items/a/b c",
                    headers, null, Map.of(), Map.of(), null, false, "app.azurecontainerapps.io", "127.0.0.1",
                    "items/a%2Fb%20c%3Fvalue%23part", null).withAuthContext(null);
            assertEquals("app.azurecontainerapps.io", request.host());
            assertEquals("127.0.0.1", request.remoteAddress());
            ContainerLifecycleManager.EndpointInfo endpoint = new ContainerLifecycleManager.EndpointInfo(
                    "127.0.0.1", server.getAddress().getPort());

            Response response = new ContainerAppIngressProxy(config).proxy(request, endpoint, false);

            assertEquals(204, response.getStatus());
            assertEquals("/items/a%2Fb%20c%3Fvalue%23part", receivedPath.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void forwardsWithoutAnH2cUpgrade() throws Exception {
        AtomicReference<String> upgrade = new AtomicReference<>();
        AtomicReference<String> protocol = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            protocol.set(exchange.getProtocol());
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().containerApps().ingressTimeoutSeconds()).thenReturn(5);
            HttpHeaders headers = mock(HttpHeaders.class);
            when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
            AzureRequest request = new AzureRequest("GET", "app", "containerapps", "readyz",
                    headers, null, Map.of(), Map.of(), null, false, "app.azurecontainerapps.io", "127.0.0.1",
                    "readyz", null).withAuthContext(null);
            ContainerLifecycleManager.EndpointInfo endpoint = new ContainerLifecycleManager.EndpointInfo(
                    "127.0.0.1", server.getAddress().getPort());

            Response response = new ContainerAppIngressProxy(config).proxy(request, endpoint, false);

            assertEquals(200, response.getStatus());
            assertNull(upgrade.get());
            assertEquals("HTTP/1.1", protocol.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void http2TransportForwardsOverHttp2() throws Exception {
        AtomicReference<String> upgrade = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().containerApps().ingressTimeoutSeconds()).thenReturn(5);
            HttpHeaders headers = mock(HttpHeaders.class);
            when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
            AzureRequest request = new AzureRequest("GET", "app", "containerapps", "readyz",
                    headers, null, Map.of(), Map.of(), null, false, "app.azurecontainerapps.io", "127.0.0.1",
                    "readyz", null).withAuthContext(null);
            ContainerLifecycleManager.EndpointInfo endpoint = new ContainerLifecycleManager.EndpointInfo(
                    "127.0.0.1", server.getAddress().getPort());

            Response response = new ContainerAppIngressProxy(config).proxy(request, endpoint, true);

            assertEquals(200, response.getStatus());
            // The JDK reaches a cleartext HTTP/2 server through an h2c upgrade.
            assertEquals("h2c", upgrade.get());
        } finally {
            server.stop(0);
        }
    }
}
