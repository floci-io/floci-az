package io.floci.az.services.blob;

import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entity tags in the form the Blob service sends: a quoted {@code 0x} and at least 15 upper-case hex digits,
 * such as {@code "0x8DC1A2B3C4D5E6F"}. Azurite writes the same shape (its {@code newEtag} in
 * {@code src/common/utils/utils.ts}). The values one instance makes grow with time and never repeat.
 */
final class BlobEtags {

    /** Milliseconds scaled up so today's clock reads as 15 hex digits. */
    private static final long SCALE = 100_000L;

    private final AtomicLong last = new AtomicLong();

    String next() {
        return next(Clock.systemUTC());
    }

    String next(Clock clock) {
        long scaled = clock.millis() * SCALE;
        long value = last.updateAndGet(previous -> Math.max(previous + 1, scaled));
        return "\"0x" + Long.toHexString(value).toUpperCase(Locale.ROOT) + "\"";
    }
}
