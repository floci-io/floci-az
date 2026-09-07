package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The patched Artemis jars replace the same-version jars inside the sidecar image, so the library version in
 * pom.xml and the image tags in application.yml must agree. A dependabot bump of artemis-amqp-protocol or
 * proton-j without the matching image (or the other way round) fails here with a message that says which.
 */
@QuarkusTest
@DisplayName("Artemis patch versions — pom.xml, embedded jars and sidecar image tags agree")
class ArtemisPatchVersionsTest {

    @Inject
    EmulatorConfig config;

    @Test
    void serviceBusImageIsTaggedWithThePatchedArtemisVersion() {
        String image = config.services().serviceBus().artemisImage();
        assertTrue(ArtemisPatchVersions.matchesImage(image), mismatch("service-bus", image));
    }

    @Test
    void eventHubImageIsTaggedWithThePatchedArtemisVersion() {
        String image = config.services().eventHub().artemisImage();
        assertTrue(ArtemisPatchVersions.matchesImage(image), mismatch("event-hub", image));
    }

    @Test
    void embeddedPatchJarsExistForTheDeclaredVersions() {
        assertTrue(ArtemisPatchVersions.class.getResource(ArtemisPatchVersions.PROTON_PATCH_RESOURCE) != null,
                "missing " + ArtemisPatchVersions.PROTON_PATCH_RESOURCE);
        assertTrue(ArtemisPatchVersions.class.getResource(ArtemisPatchVersions.ARTEMIS_AMQP_PATCH_RESOURCE) != null,
                "missing " + ArtemisPatchVersions.ARTEMIS_AMQP_PATCH_RESOURCE);
    }

    private static String mismatch(String service, String image) {
        return "floci-az.services." + service + ".artemis-image is '" + image + "' but pom.xml patches Artemis "
                + ArtemisPatchVersions.ARTEMIS_VERSION + " (proton-j " + ArtemisPatchVersions.PROTON_J_VERSION
                + "); the patched jars are copied over the same-version jars in that image, so bump both together";
    }
}
