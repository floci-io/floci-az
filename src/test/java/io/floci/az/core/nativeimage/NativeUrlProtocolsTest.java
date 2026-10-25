package io.floci.az.core.nativeimage;

import io.smallrye.config.source.yaml.YamlConfigSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks that the native image is built with the https URL protocol.
 *
 * <p>Quarkus registers only {@code http} unless {@code quarkus.native.enable-https-url-handler} is set,
 * and without it an https {@code java.net.URL} throws {@code MalformedURLException} in the native
 * binary while working in the JVM. {@code AksClusterManager.isReady} probes the k3s API server over
 * https, so losing the setting leaves every real-mode AKS cluster in {@code Creating}. No JVM test can
 * see that, so the setting itself is pinned here.
 */
@DisplayName("native image: the https URL protocol is enabled")
class NativeUrlProtocolsTest {

    @Test
    void httpsUrlHandlerIsEnabledForTheNativeBuild() throws Exception {
        URL yaml = getClass().getClassLoader().getResource("application.yml");
        assertNotNull(yaml, "application.yml should be on the classpath");

        YamlConfigSource source = new YamlConfigSource(yaml);
        assertEquals("true", source.getValue("quarkus.native.enable-https-url-handler"),
                "AksClusterManager.isReady opens an https URL; the native image needs the https URL handler");
    }
}
