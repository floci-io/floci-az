package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;

import java.util.Locale;

/** URL helpers for handlers that echo the caller's origin back in generated URLs. */
public final class RequestUrls {

    private RequestUrls() {
    }

    /**
     * The scheme the caller used: {@code X-Forwarded-Proto} when a TLS-terminating reverse proxy
     * fronts the emulator (the proxy-to-emulator hop is plaintext, but the client's scheme is what
     * generated URLs must carry), and the transport otherwise. Same rule as the Cosmos, PostgreSQL
     * and SQL handlers apply to the endpoints they advertise.
     */
    public static String resolveScheme(AzureRequest request) {
        String forwarded = request.headers() == null
                ? null : request.headers().getHeaderString("X-Forwarded-Proto");
        if (forwarded != null && !forwarded.isBlank()) {
            // A proxy chain may send a comma-separated list; the first value is the client's.
            String scheme = forwarded.split(",")[0].trim().toLowerCase(Locale.ROOT);
            if (scheme.equals("http") || scheme.equals("https")) {
                return scheme;
            }
        }
        return request.secure() ? "https" : "http";
    }

    /** Base URL as seen by the caller — Host header when present, configured base URL otherwise. */
    public static String resolveBaseUrl(AzureRequest request, EmulatorConfig config) {
        String host = request.headers() == null ? null : request.headers().getHeaderString("Host");
        if (host == null || host.isBlank()) {
            return config.effectiveBaseUrl();
        }
        String scheme = request.secure() ? "https" : "http";
        return scheme + "://" + host;
    }
}
