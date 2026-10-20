package io.floci.az.services.blob;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlobEtagsTest {

    @Test
    void anEtagIsAQuotedHexStringOfAtLeastFifteenDigits() {
        String etag = new BlobEtags().next();
        assertTrue(etag.matches("^\"0x[A-F0-9]{15,}\"$"), etag);
    }

    @Test
    void twoEtagsInTheSameMillisecondDiffer() {
        Clock frozen = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
        BlobEtags etags = new BlobEtags();
        assertNotEquals(etags.next(frozen), etags.next(frozen));
    }
}
