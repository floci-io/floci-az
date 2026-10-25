package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LinkedTemplates: a download is bounded by its deadline, body included")
class LinkedTemplatesTimeoutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Released when the test ends, so the stalled handler returns. */
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;

    @BeforeEach
    void stallingServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/stall.json", exchange -> {
            // Headers and the start of the body, then nothing: the request timeout has already ended.
            exchange.sendResponseHeaders(200, 1000);
            OutputStream body = exchange.getResponseBody();
            body.write("{\"resources\": [".getBytes(StandardCharsets.UTF_8));
            body.flush();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void aServerThatStallsAfterItsHeadersIsCutOffAtTheDeadline() {
        String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/stall.json";
        LinkedTemplates links = new LinkedTemplates(Duration.ofSeconds(1));

        TemplateException refused = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
            assertThrows(TemplateException.class, () -> links.inline(MAPPER.readTree(
                "{\"mode\": \"Incremental\", \"templateLink\": {\"uri\": \"" + uri + "\"}}"))));

        assertEquals(LinkedTemplates.INVALID_CONTENT_LINK, refused.code());
        assertTrue(refused.getMessage().contains("did not finish downloading within 1 seconds"), refused.getMessage());
    }
}
