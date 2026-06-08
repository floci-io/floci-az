package io.floci.az.services.appconfig;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates Azure App Configuration {@code Sync-Token} response headers.
 *
 * <p>The SDK sync-token policy parses the {@code <id>=<base64value>;sn=<sequence>} form and tracks the
 * sequence number, so the value must be well-formed. The sequence is monotonic per account.</p>
 */
@ApplicationScoped
public class SyncTokens {

    private static final String TOKEN_ID = "jtqGc1I4";

    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    /** Returns the next monotonic {@code Sync-Token} for the given account. */
    public String next(String account) {
        long sn = sequences.computeIfAbsent(account, a -> new AtomicLong()).incrementAndGet();
        String value = Base64.getEncoder().encodeToString(("0:" + sn).getBytes(StandardCharsets.UTF_8));
        return TOKEN_ID + "=" + value + ";sn=" + sn;
    }

    /** Drops per-account sequences (used by the admin reset endpoint). */
    public void clear() {
        sequences.clear();
    }
}
