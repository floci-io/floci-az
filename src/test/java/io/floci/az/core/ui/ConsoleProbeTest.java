package io.floci.az.core.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleProbeTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "floci/floci-ui",
        "floci/floci-ui:0.7.0",
        "FLOCI/floci-ui:latest",
        "docker.io/floci/floci-ui:0.7.0",
        "index.docker.io/floci/floci-ui",
        "floci/floci-ui@sha256:0123456789abcdef",
        "docker.io/floci/floci-ui:0.7.0@sha256:0123456789abcdef"
    })
    void theFlociUiImageIsRecognisedWithAnyTagDigestOrDockerHubPrefix(String image) {
        assertTrue(ConsoleProbe.isFlociUiImage(image), image);
        assertEquals(ConsoleProbe.flociUi(), ConsoleProbe.forImage(image));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ghcr.io/floci/floci-ui", "acme/floci-ui:1.0", "floci/floci-ui-next", "localhost:5000/console"})
    void anyOtherImageGetsTheContractDefaults(String image) {
        assertFalse(ConsoleProbe.isFlociUiImage(image), image);
        assertEquals(ConsoleProbe.contractV2(), ConsoleProbe.forImage(image));
    }

    @Test
    void theContractProbesApiHealthAndMayReceiveFlociEndpoint() {
        ConsoleProbe probe = ConsoleProbe.contractV2();
        assertEquals("/api/health", probe.healthPath());
        assertEquals("status", probe.readyField());
        assertEquals("ok", probe.readyValue());
        assertEquals("unavailable", probe.unavailableValue());
        assertTrue(probe.setsFlociEndpoint());
        assertEquals("/", probe.landingPath(), "a v2 console opens on the cloud FLOCI_CLOUD names");
    }

    @Test
    void theBuiltInFlociUiProfileProbesItsAzureStatusAndWithholdsFlociEndpoint() {
        ConsoleProbe probe = ConsoleProbe.flociUi();
        assertEquals("/api/clouds/azure/status", probe.healthPath());
        assertEquals("runtime", probe.readyField());
        assertEquals("reachable", probe.readyValue());
        assertFalse(probe.setsFlociEndpoint(), "floci-ui 0.7 reads FLOCI_ENDPOINT as the AWS endpoint");
        assertEquals("/console/azure", probe.landingPath(), "floci-ui 0.7 opens on AWS at its root");
    }
}
