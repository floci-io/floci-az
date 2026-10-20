package io.floci.az.services.monitor;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The decoding of a Logs Ingestion upload body, and the size limit a gzipped one is held to. */
class MonitorIngestionBodyTest {

    @Test
    void aGzippedBodyWithinTheLimitDecodesInFull() throws IOException {
        byte[] json = "[{\"Message\":\"hello\"}]".getBytes(StandardCharsets.UTF_8);

        try (InputStream decoded = MonitorHandler.decodedBody(
                new ByteArrayInputStream(gzip(json)), "gzip", json.length)) {
            assertArrayEquals(json, decoded.readAllBytes());
        }
    }

    @Test
    void aGzippedBodyThatDecodesPastTheLimitIsRefused() throws IOException {
        byte[] json = ("[" + "{\"Message\":\"aaaaaaaaaaaaaaaa\"},".repeat(10_000) + "{}]").getBytes(StandardCharsets.UTF_8);
        byte[] compressed = gzip(json);

        try (InputStream decoded = MonitorHandler.decodedBody(
                new ByteArrayInputStream(compressed), "gzip", compressed.length * 2L)) {
            assertThrows(MonitorHandler.DecodedBodyTooLargeException.class, decoded::readAllBytes);
        }
    }

    @Test
    void aPlainBodyIsPassedThroughUnwrapped() throws IOException {
        InputStream body = new ByteArrayInputStream(new byte[] {'[', ']'});

        assertSame(body, MonitorHandler.decodedBody(body, null, 1));
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        }
        return out.toByteArray();
    }
}
