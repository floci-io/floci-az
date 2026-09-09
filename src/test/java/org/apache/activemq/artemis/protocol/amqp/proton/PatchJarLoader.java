package org.apache.activemq.artemis.protocol.amqp.proton;

import io.floci.az.services.servicebus.ArtemisPatchVersions;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opens the patched {@code artemis-amqp-protocol} jar the build writes.
 *
 * The classes it holds shadow ones inside the stock Artemis jar, so they are not on the test
 * classpath and a test reaches them through a loader of its own.
 *
 * The jar's name carries the Artemis version, and that version lives in the pom alone — the same
 * value names the jar, the paths inside the sidecar container and the image tag, so they cannot
 * drift apart. Spelling the version here as a literal would put it back in a second place: the
 * next Artemis bump would then fail these tests with a file-not-found instead of the message
 * {@code ArtemisPatchVersionsTest} exists to give.
 */
final class PatchJarLoader {

    private PatchJarLoader() {
    }

    /** A loader over the patched jar, with the test classpath as its parent. */
    static URLClassLoader open() throws Exception {
        // The constant is a classpath resource ("/artemis/…"); the same file is written under
        // target/classes, so the resource path resolves against it once the leading slash is gone.
        Path jar = Path.of("target", "classes")
                .resolve(ArtemisPatchVersions.ARTEMIS_AMQP_PATCH_RESOURCE.substring(1));
        if (!Files.exists(jar)) {
            throw new IllegalStateException(
                    "Patched jar not found: " + jar + " — it is written by the "
                            + "package-servicebus-artemis-patches step in pom.xml, so run the "
                            + "build rather than the test alone");
        }
        return new URLClassLoader(new URL[]{jar.toUri().toURL()},
                PatchJarLoader.class.getClassLoader());
    }
}
