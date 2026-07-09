package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;

/** URL helpers for handlers that echo the caller's origin back in generated URLs. */
public final class RequestUrls {

    private RequestUrls() {
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
