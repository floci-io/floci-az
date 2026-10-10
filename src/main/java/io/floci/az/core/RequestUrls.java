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

    /**
     * The {@code host[:port]} the caller addressed, or {@code null} when the request carries none.
     * The authority captured from the transport comes first: HTTP/2 sends it as {@code :authority}
     * and no {@code Host} header, so reading only {@code Host} loses it. Requests built without a
     * transport fall back to the {@code Host} header.
     */
    public static String resolveAuthority(AzureRequest request) {
        if (request.authority() != null && !request.authority().isBlank()) {
            return request.authority();
        }
        String host = request.headers() == null ? null : request.headers().getHeaderString("Host");
        return host == null || host.isBlank() ? null : host;
    }

    /**
     * Base URL as seen by the caller: the request authority when present, configured base URL
     * otherwise. The scheme comes from {@link #resolveScheme}, so a URL generated behind a
     * TLS-terminating proxy keeps the client's https rather than the plaintext proxy-to-emulator
     * hop. Clients that refuse to send credentials over http, the az CLI among them, cannot follow
     * a downgraded URL.
     */
    public static String resolveBaseUrl(AzureRequest request, EmulatorConfig config) {
        String authority = resolveAuthority(request);
        if (authority == null) {
            return config.effectiveBaseUrl();
        }
        return resolveScheme(request) + "://" + authority;
    }
}
