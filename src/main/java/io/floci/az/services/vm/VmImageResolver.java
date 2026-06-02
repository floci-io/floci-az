package io.floci.az.services.vm;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves an Azure {@code storageProfile.imageReference} (publisher / offer / sku) to a
 * Docker image URI, used when running VMs in non-mocked mode (phase 2).
 *
 * <p>Recognises the common Linux marketplace images; unknown references fall back to
 * {@code ubuntu:22.04}. Windows images are not supported locally and also fall back.</p>
 */
@ApplicationScoped
public class VmImageResolver {

    private static final Logger LOG = Logger.getLogger(VmImageResolver.class);

    private static final String DEFAULT_IMAGE = "ubuntu:22.04";

    // Keyed by "<publisher>/<offer>/<sku>" lower-cased. Kept small and explicit, mirroring
    // the AWS sibling's AmiImageResolver; the heuristic below covers the long tail.
    private static final Map<String, String> BUILTIN_MAPPINGS = Map.ofEntries(
            Map.entry("canonical/0001-com-ubuntu-server-jammy/22_04-lts",  "ubuntu:22.04"),
            Map.entry("canonical/0001-com-ubuntu-server-jammy/22_04-lts-gen2", "ubuntu:22.04"),
            Map.entry("canonical/0001-com-ubuntu-server-noble/24_04-lts",  "ubuntu:24.04"),
            Map.entry("canonical/0001-com-ubuntu-server-focal/20_04-lts",  "ubuntu:20.04"),
            Map.entry("canonical/ubuntuserver/18.04-lts",                  "ubuntu:18.04"),
            Map.entry("debian/debian-12/12",                               "debian:12"),
            Map.entry("debian/debian-11/11",                               "debian:11"),
            Map.entry("almalinux/almalinux/9-gen2",                        "almalinux:9"),
            Map.entry("rockylinux/rockylinux/9-base",                      "rockylinux:9")
    );

    /** Resolves a typed publisher/offer/sku triple to a Docker image URI. */
    public String resolve(String publisher, String offer, String sku) {
        String pub = lower(publisher);
        String off = lower(offer);
        String s = lower(sku);

        String key = pub + "/" + off + "/" + s;
        String mapped = BUILTIN_MAPPINGS.get(key);
        if (mapped != null) {
            return mapped;
        }

        // Heuristic fall-through for the long tail of marketplace SKUs.
        if (off.contains("ubuntu") || pub.contains("canonical")) {
            if (s.startsWith("24") || off.contains("noble")) { return "ubuntu:24.04"; }
            if (s.startsWith("20") || off.contains("focal")) { return "ubuntu:20.04"; }
            if (s.startsWith("18")) { return "ubuntu:18.04"; }
            return "ubuntu:22.04";
        }
        if (off.contains("debian") || pub.contains("debian")) {
            return s.startsWith("11") ? "debian:11" : "debian:12";
        }
        if (off.contains("alpine") || s.contains("alpine")) {
            return "alpine:latest";
        }

        LOG.warnv("Unknown imageReference {0}/{1}/{2}; falling back to {3}",
                publisher, offer, sku, DEFAULT_IMAGE);
        return DEFAULT_IMAGE;
    }

    /** Convenience overload that resolves directly from a parsed imageReference map. */
    @SuppressWarnings("unchecked")
    public String resolve(Map<String, Object> imageReference) {
        if (imageReference == null) {
            return DEFAULT_IMAGE;
        }
        return resolve(
                str(imageReference.get("publisher")),
                str(imageReference.get("offer")),
                str(imageReference.get("sku")));
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
