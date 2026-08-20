package io.floci.az.services.functions;

import com.sun.net.httpserver.HttpServer;
import io.floci.az.core.AzureRequest;
import io.floci.az.services.functions.FunctionModels.FunctionDefinition;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FunctionsExecutorServiceTest {

    @Test
    void emptyRoutePrefixProxiesDirectlyToFunctionRoute() throws Exception {
        assertProxiedPath("", "/hello");
    }

    @Test
    void missingRoutePrefixKeepsAzureApiDefault() throws Exception {
        assertProxiedPath(null, "/api/hello");
    }

    private static void assertProxiedPath(String routePrefix, String expectedPath) throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        try {
            FunctionDefinition definition = new FunctionDefinition(
                    "app", "hello", "account", "python", "Python|3.12", "function_app.hello",
                    10, null, "/tmp/functions/hello", Instant.now(), true, routePrefix);
            WarmPool pool = mock(WarmPool.class);
            ContainerHandle handle = new ContainerHandle(
                    "container", definition.appKey(), "127.0.0.1", server.getAddress().getPort());
            when(pool.acquire(eq(definition), eq(List.of(definition)))).thenReturn(handle);

            FunctionsExecutorService executor = new FunctionsExecutorService(pool);
            Response response = executor.invoke(definition, List.of(definition), request());

            assertEquals(200, response.getStatus());
            assertEquals(expectedPath, receivedPath.get());
        } finally {
            server.stop(0);
        }
    }

    private static AzureRequest request() {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        return new AzureRequest("GET", "account", "functions", "api/app/hello",
                headers, null, Map.of(), null, false);
    }
}