package io.floci.az.services.acr;

import com.sun.net.httpserver.HttpServer;
import io.floci.az.core.AzureRequest;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The proxy against a stand-in registry: what the shared container actually receives, and what the
 * client gets back. {@link AcrRegistryProxyTest} covers the path and body rewriting in isolation.
 */
@DisplayName("AcrRegistryProxy: proxying to the shared registry")
class AcrRegistryProxyHttpTest {

    @Test
    void prefixesTheRepositoryAndStripsThePrefixFromTheUploadLocation() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        HttpServer registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = registry.getAddress().getPort();
        registry.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().getRawPath());
            // registry:2 answers with an absolute URL built from the Host it was called on.
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + port + "/v2/myreg/app/blobs/uploads/abc-123?_state=opaque");
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        registry.start();

        try {
            Response response = proxy(request("POST", "v2/app/blobs/uploads/", null, Map.of()), port);

            assertEquals(202, response.getStatus());
            assertEquals("/v2/myreg/app/blobs/uploads/", receivedPath.get());
            assertEquals("/v2/app/blobs/uploads/abc-123?_state=opaque",
                    response.getHeaderString("Location"));
        } finally {
            registry.stop(0);
        }
    }

    @Test
    void streamsTheRequestBodyWithTheLengthTheClientDeclared() throws Exception {
        byte[] blob = "a-layer-worth-of-bytes".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedLength = new AtomicReference<>();
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        HttpServer registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registry.createContext("/", exchange -> {
            receivedLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            receivedQuery.set(exchange.getRequestURI().getQuery());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        registry.start();

        try {
            AzureRequest request = request("PUT", "v2/app/blobs/uploads/abc-123",
                    new ByteArrayInputStream(blob),
                    Map.of("Content-Length", String.valueOf(blob.length)),
                    Map.of("digest", List.of("sha256:abc")));

            Response response = proxy(request, registry.getAddress().getPort());

            assertEquals(201, response.getStatus());
            assertEquals(new String(blob, StandardCharsets.UTF_8), receivedBody.get());
            // Streamed, not chunked: a blob upload is framed by its length.
            assertEquals(String.valueOf(blob.length), receivedLength.get());
            // Query parameters survive the hop: the registry reads the digest it was sent.
            assertEquals("digest=sha256:abc", receivedQuery.get());
        } finally {
            registry.stop(0);
        }
    }

    @Test
    void streamsAnUploadThatDeclaredNoLengthInsteadOfBufferingIt() throws Exception {
        // A client that streams a layer of unknown size sends it chunked. Forwarding it chunked in
        // turn keeps it out of memory; reading it whole to re-declare a length would not.
        byte[] blob = "a-layer-of-unknown-size".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedEncoding = new AtomicReference<>();
        AtomicReference<String> receivedLength = new AtomicReference<>();
        HttpServer registry = server(exchange -> {
            receivedEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            receivedLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });

        try {
            AzureRequest request = request("PATCH", "v2/app/blobs/uploads/abc-123",
                    new ByteArrayInputStream(blob), Map.of());

            Response response = proxy(request, registry.getAddress().getPort());

            assertEquals(202, response.getStatus());
            assertEquals(new String(blob, StandardCharsets.UTF_8), receivedBody.get());
            assertEquals("chunked", receivedEncoding.get());
            assertNull(receivedLength.get(), "a chunked body declares no length");
        } finally {
            registry.stop(0);
        }
    }

    @Test
    void doesNotChunkABodylessRequestThatDeclaredNoLength() {
        // A GET carries nothing to stream. Declaring no length must not turn it into a chunked
        // request the client never made.
        AtomicReference<String> receivedEncoding = new AtomicReference<>();
        HttpServer registry = server(exchange -> {
            receivedEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        try {
            AzureRequest request = request("GET", "v2/app/blobs/sha256:abc",
                    new ByteArrayInputStream(new byte[0]), Map.of());

            assertEquals(200, proxy(request, registry.getAddress().getPort()).getStatus());
            assertNull(receivedEncoding.get());
        } finally {
            registry.stop(0);
        }
    }

    @Test
    void catalogReportsOnlyThisRegistrysRepositories() {
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        HttpServer registry = catalogServer(receivedQuery, "myreg/app", "otherreg/app");

        try {
            Response response = proxy(request("GET", "v2/_catalog", null, Map.of()),
                    registry.getAddress().getPort());

            assertEquals(200, response.getStatus());
            assertEquals("{\"repositories\":[\"app\"]}", new String((byte[]) response.getEntity()));
            // Unpaginated: seeded at the head of our block, with no page size and so no next link.
            assertEquals("last=myreg%2F", receivedQuery.get());
            assertNull(response.getHeaderString("Link"));
        } finally {
            registry.stop(0);
        }
    }

    @Test
    void catalogSeedsTheCursorAtThisRegistrysBlockAndAsksForOneExtra() {
        // The seed makes the container count our repositories rather than everyone's, so a page
        // comes back full. The extra one is what tells us whether a next page exists.
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        HttpServer registry = catalogServer(receivedQuery, "myreg/app", "myreg/web", "myreg/zebra");

        try {
            Response response = proxy(request("GET", "v2/_catalog", null, Map.of(),
                    Map.of("n", List.of("2"))), registry.getAddress().getPort());

            assertEquals(200, response.getStatus());
            assertEquals("last=myreg%2F&n=3", receivedQuery.get());
            assertEquals("{\"repositories\":[\"app\",\"web\"]}", new String((byte[]) response.getEntity()));
            assertEquals("</v2/_catalog?last=web&n=2>; rel=\"next\"", response.getHeaderString("Link"));
        } finally {
            registry.stop(0);
        }
    }

    @Test
    void catalogAnswersAnEmptyPageWithoutAskingTheContainer() {
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        HttpServer registry = catalogServer(receivedQuery, "myreg/app", "myreg/web");

        try {
            Response response = proxy(request("GET", "v2/_catalog", null, Map.of(),
                    Map.of("n", List.of("0"))), registry.getAddress().getPort());

            assertEquals(200, response.getStatus());
            assertEquals("{\"repositories\":[]}", new String((byte[]) response.getEntity()));
            assertNull(response.getHeaderString("Link"));
            assertNull(receivedQuery.get(), "a page of nothing needs no container request");
        } finally {
            registry.stop(0);
        }
    }

    @Test
    void catalogPrefixesTheCursorTheClientSendsBack() {
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        HttpServer registry = catalogServer(receivedQuery, "myreg/zebra", "otherreg/db");

        try {
            Response response = proxy(request("GET", "v2/_catalog", null, Map.of(),
                            Map.of("n", List.of("2"), "last", List.of("web"))),
                    registry.getAddress().getPort());

            assertEquals(200, response.getStatus());
            assertEquals("last=myreg%2Fweb&n=3", receivedQuery.get());
            // The block ends inside this page, so it is the last one and carries no next link.
            assertEquals("{\"repositories\":[\"zebra\"]}", new String((byte[]) response.getEntity()));
            assertNull(response.getHeaderString("Link"));
        } finally {
            registry.stop(0);
        }
    }

    @Test
    void catalogEndsThePageWhenTheExtraRepositoryIsOutsideThisRegistry() {
        // The container advertises a next page whenever the page it returned was full, so its own
        // Link cannot say whether we have more. The extra repository can, and it does not leak.
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        HttpServer registry = catalogServer(receivedQuery, "myreg/app", "myreg/web", "otherreg/db");

        try {
            Response response = proxy(request("GET", "v2/_catalog", null, Map.of(),
                    Map.of("n", List.of("2"))), registry.getAddress().getPort());

            assertEquals("{\"repositories\":[\"app\",\"web\"]}", new String((byte[]) response.getEntity()));
            assertNull(response.getHeaderString("Link"));
        } finally {
            registry.stop(0);
        }
    }

    @Test
    void catalogNeverForwardsTheBackendsOwnNextLink() {
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        HttpServer registry = server(exchange -> {
            receivedQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"repositories\":[\"myreg/app\"]}".getBytes(StandardCharsets.UTF_8);
            // The backend cursor names the internal repository and must never reach the client.
            exchange.getResponseHeaders().add("Link", "</v2/_catalog?last=myreg%2Fapp&n=1>; rel=\"next\"");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        try {
            Response response = proxy(request("GET", "v2/_catalog", null, Map.of()),
                    registry.getAddress().getPort());

            assertNull(response.getHeaderString("Link"));
        } finally {
            registry.stop(0);
        }
    }

    /** A stand-in catalog answering every request with {@code repositories}, recording its query. */
    private static HttpServer catalogServer(AtomicReference<String> receivedQuery, String... repositories) {
        String names = String.join(",", List.of(repositories).stream().map(r -> "\"" + r + "\"").toList());
        byte[] body = ("{\"repositories\":[" + names + "]}").getBytes(StandardCharsets.UTF_8);
        return server(exchange -> {
            receivedQuery.set(exchange.getRequestURI().getRawQuery());
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    @Test
    void streamsTheResponseBodyBack() throws Exception {
        HttpServer registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registry.createContext("/", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Docker-Content-Digest", "sha256:abc");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        registry.start();

        try {
            Response response = proxy(request("GET", "v2/app/blobs/sha256:abc", null, Map.of()),
                    registry.getAddress().getPort());

            assertEquals(200, response.getStatus());
            assertEquals("sha256:abc", response.getHeaderString("Docker-Content-Digest"));
            assertEquals("{}", new String(((InputStream) response.getEntity()).readAllBytes(),
                    StandardCharsets.UTF_8));
        } finally {
            registry.stop(0);
        }
    }

    @Test
    void reportsTheRegistryUnavailableInsteadOfFailingTheRequest() throws Exception {
        // A port nothing is listening on: the container died, or never started.
        HttpServer closed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = closed.getAddress().getPort();
        closed.start();
        closed.stop(0);

        Response response = proxy(request("GET", "v2/app/tags/list", null, Map.of()), port);

        assertEquals(502, response.getStatus());
        assertTrue(response.getEntity().toString().contains("UNAVAILABLE"), "expected the registry error shape");
    }

    @Test
    void headReportsTheBackendsStatusAndContentLength() {
        // Docker sizes a blob it is about to push from this response: the length is the point of
        // the request, and HEAD carries no body to re-derive it from.
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        HttpServer registry = server(exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            exchange.getResponseHeaders().add("Content-Length", "4096");
            exchange.getResponseHeaders().add("Docker-Content-Digest", "sha256:abc");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        try {
            Response response = proxy(request("HEAD", "v2/app/blobs/sha256:abc", null, Map.of()),
                    registry.getAddress().getPort());

            assertEquals(200, response.getStatus());
            assertEquals("HEAD", receivedMethod.get());
            assertEquals("4096", response.getHeaderString("Content-Length"));
            assertEquals("sha256:abc", response.getHeaderString("Docker-Content-Digest"));
        } finally {
            registry.stop(0);
        }
    }

    @Test
    void forwardsTheClientsQueryStringVerbatim() {
        // registry:2 mints an opaque _state for an upload session and expects it back byte for byte.
        // Rebuilding the query from decoded parameters would not round-trip it.
        String rawQuery = "_state=bXlyZWcvYXBw_9-x.Y%3D%3D&digest=sha256%3Aabc";
        AtomicReference<String> receivedRawQuery = new AtomicReference<>();
        HttpServer registry = server(exchange -> {
            receivedRawQuery.set(exchange.getRequestURI().getRawQuery());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        try {
            AzureRequest request = request("PUT", "v2/app/blobs/uploads/abc-123", null, Map.of(),
                    Map.of("_state", List.of("bXlyZWcvYXBw_9-x.Y=="), "digest", List.of("sha256:abc")),
                    rawQuery);

            Response response = proxy(request, registry.getAddress().getPort());

            assertEquals(204, response.getStatus());
            assertEquals(rawQuery, receivedRawQuery.get());
        } finally {
            registry.stop(0);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** A started stand-in registry answering every path with {@code handler}. */
    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) {
        try {
            HttpServer registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            registry.createContext("/", handler);
            registry.start();
            return registry;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not start the stand-in registry", e);
        }
    }

    private static Response proxy(AzureRequest request, int port) {
        return new AcrRegistryProxy().proxy(request, "myreg", "127.0.0.1:" + port);
    }

    private static AzureRequest request(String method, String path, InputStream body,
                                        Map<String, String> headers) {
        return request(method, path, body, headers, Map.of(), null);
    }

    private static AzureRequest request(String method, String path, InputStream body,
                                        Map<String, String> headers,
                                        Map<String, List<String>> queryParams) {
        return request(method, path, body, headers, queryParams, null);
    }

    private static AzureRequest request(String method, String path, InputStream body,
                                        Map<String, String> headers,
                                        Map<String, List<String>> queryParams, String rawQuery) {
        MultivaluedMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        headers.forEach(requestHeaders::add);
        HttpHeaders httpHeaders = mock(HttpHeaders.class);
        when(httpHeaders.getRequestHeaders()).thenReturn(requestHeaders);
        headers.forEach((name, value) -> when(httpHeaders.getHeaderString(name)).thenReturn(value));

        return new AzureRequest(method, "myreg", "acr", path, httpHeaders, body,
                Map.of(), queryParams, null, true, "myreg.azurecr.io", "127.0.0.1", path, rawQuery);
    }
}
