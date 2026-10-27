package io.floci.az.core.ui;

import java.util.Locale;

/**
 * How floci-az asks a console whether it is ready, and whether the console may be given {@code FLOCI_ENDPOINT}.
 *
 * <p>A Console Contract v2 console serves {@code GET /api/health} with {@code status} {@code ok} or
 * {@code unavailable}. The {@code floci/floci-ui} releases that predate the contract serve neither: their Azure
 * readiness is {@code runtime} on {@code GET /api/clouds/azure/status}, and they read {@code FLOCI_ENDPOINT} as
 * the AWS endpoint, so it must not be set for them. They also open on AWS at their root, so the browser is sent
 * straight to their Azure console instead.
 *
 * @param healthPath        the path floci-az probes on the console
 * @param readyField        the JSON field holding the console's verdict
 * @param readyValue        the value of {@code readyField} that means ready
 * @param unavailableValue  the value that means the console cannot reach floci-az
 * @param setsFlociEndpoint whether the console receives {@code FLOCI_ENDPOINT}
 * @param landingPath       where the browser is sent on the console, so it opens on Azure
 */
public record ConsoleProbe(String healthPath, String readyField, String readyValue, String unavailableValue,
                           boolean setsFlociEndpoint, String landingPath) {

    private static final String FLOCI_UI_REPOSITORY = "floci/floci-ui";

    /** The Console Contract v2 defaults. A v2 console opens on the cloud named by {@code FLOCI_CLOUD}. */
    public static ConsoleProbe contractV2() {
        return new ConsoleProbe("/api/health", "status", "ok", "unavailable", true, "/");
    }

    /** The {@code floci/floci-ui} releases up to 0.7.x, which predate the contract. */
    public static ConsoleProbe flociUi() {
        return new ConsoleProbe("/api/clouds/azure/status", "runtime", "reachable", "unavailable", false,
                "/console/azure");
    }

    /** The probe for {@code image}: the built-in profile for {@code floci/floci-ui}, the contract otherwise. */
    public static ConsoleProbe forImage(String image) {
        return isFlociUiImage(image) ? flociUi() : contractV2();
    }

    /** True when {@code image} names {@code floci/floci-ui}, with any tag, digest or Docker Hub prefix. */
    static boolean isFlociUiImage(String image) {
        if (image == null || image.isBlank()) {
            return false;
        }
        String repository = image.trim().toLowerCase(Locale.ROOT);
        int digest = repository.indexOf('@');
        if (digest >= 0) {
            repository = repository.substring(0, digest);
        }
        int lastSlash = repository.lastIndexOf('/');
        int tag = repository.lastIndexOf(':');
        if (tag > lastSlash) {
            repository = repository.substring(0, tag);
        }
        for (String prefix : new String[] {"docker.io/", "index.docker.io/", "registry-1.docker.io/"}) {
            if (repository.startsWith(prefix)) {
                repository = repository.substring(prefix.length());
                break;
            }
        }
        return FLOCI_UI_REPOSITORY.equals(repository);
    }
}
