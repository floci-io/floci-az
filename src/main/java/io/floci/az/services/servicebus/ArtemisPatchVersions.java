package io.floci.az.services.servicebus;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The Artemis and proton-j versions the build patched, read from the {@code artemis/patch-versions.properties}
 * file the Maven build writes next to the patch jars. The patched jars replace the same-version jars inside the
 * Artemis sidecar image, so the resource names below, the paths inside the container and the image tag in
 * {@code application.yml} must all agree; keeping the versions in one place (the pom) makes a bump a two-line
 * change and lets {@code ArtemisPatchVersionsTest} catch a library bump that forgot the image.
 */
public final class ArtemisPatchVersions {

    private static final String VERSIONS_RESOURCE = "/artemis/patch-versions.properties";
    private static final Properties VERSIONS = load();

    public static final String ARTEMIS_VERSION = require("artemis.version");
    public static final String PROTON_J_VERSION = require("proton-j.version");

    /** Embedded resource holding the patched proton-j jar. */
    public static final String PROTON_PATCH_RESOURCE =
            "/artemis/proton-j-" + PROTON_J_VERSION + "-floci-az-proton-patch.jar";
    /** Embedded resource holding the patched artemis-amqp-protocol jar. */
    public static final String ARTEMIS_AMQP_PATCH_RESOURCE =
            "/artemis/artemis-amqp-protocol-" + ARTEMIS_VERSION + "-floci-az-artemis-amqp-patch.jar";
    /** Where the stock proton-j jar lives inside the {@code apache/activemq-artemis} image. */
    public static final String PROTON_J_CONTAINER_PATH =
            "/opt/activemq-artemis/lib/proton-j-" + PROTON_J_VERSION + ".jar";
    /** Where the stock artemis-amqp-protocol jar lives inside the {@code apache/activemq-artemis} image. */
    public static final String ARTEMIS_AMQP_CONTAINER_PATH =
            "/opt/activemq-artemis/lib/artemis-amqp-protocol-" + ARTEMIS_VERSION + ".jar";

    private ArtemisPatchVersions() {
    }

    /** True when {@code imageRef} (e.g. {@code apache/activemq-artemis:2.44.0}) is tagged with the patched Artemis version. */
    public static boolean matchesImage(String imageRef) {
        int colon = imageRef.lastIndexOf(':');
        if (colon < 0 || imageRef.lastIndexOf('/') > colon) {
            return false;
        }
        String tag = imageRef.substring(colon + 1);
        return tag.equals(ARTEMIS_VERSION) || tag.startsWith(ARTEMIS_VERSION + "-");
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream stream = ArtemisPatchVersions.class.getResourceAsStream(VERSIONS_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Embedded Artemis resource not found: " + VERSIONS_RESOURCE
                        + " (written by the package-servicebus-artemis-patches step in pom.xml)");
            }
            properties.load(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + VERSIONS_RESOURCE, e);
        }
        return properties;
    }

    private static String require(String key) {
        String value = VERSIONS.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(VERSIONS_RESOURCE + " is missing " + key);
        }
        return value.trim();
    }
}
