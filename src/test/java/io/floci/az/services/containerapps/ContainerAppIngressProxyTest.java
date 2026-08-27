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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                    headers, null, Map.of(), Map.of(), null, false, "127.0.0.1",
                    "items/a%2Fb%20c%3Fvalue%23part");
            var endpoint = new ContainerLifecycleManager.EndpointInfo(
                    "127.0.0.1", server.getAddress().getPort());

            Response response = new ContainerAppIngressProxy(config).proxy(request, endpoint);

            assertEquals(204, response.getStatus());
            assertEquals("/items/a%2Fb%20c%3Fvalue%23part", receivedPath.get());
        } finally {
            server.stop(0);
        }
    }
}
