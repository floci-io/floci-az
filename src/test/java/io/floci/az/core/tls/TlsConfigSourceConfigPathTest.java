package io.floci.az.core.tls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the Windows TLS startup failure: SmallRye Config strips
 * backslashes from property values, so certificate paths emitted by
 * {@link TlsConfigSource} must never contain them (e.g.
 * {@code D:\data\tls\cert.crt} used to reach Quarkus as
 * {@code D:datatlscert.crt}, failing startup with {@code NoSuchFileException}).
 */
class TlsConfigSourceConfigPathTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        System.setProperty("floci-az.tls.enabled", "true");
        System.setProperty("floci-az.tls.self-signed", "true");
        System.setProperty("floci-az.storage.persistent-path", tempDir.toString());
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("floci-az.tls.enabled");
        System.clearProperty("floci-az.tls.self-signed");
        System.clearProperty("floci-az.storage.persistent-path");
        System.clearProperty("floci-az.tls.cert-path");
        System.clearProperty("floci-az.tls.key-path");
    }

    // --- pure path conversion ---

    @Test
    void windowsSeparatorsAreSwappedForForwardSlashes() {
        assertEquals("D:/Dev/floci-az/data/tls/cert.crt",
                TlsConfigSource.toConfigPath("D:\\Dev\\floci-az\\data\\tls\\cert.crt", '\\'));
    }

    @Test
    void unixPathsAreLeftUntouched() {
        assertEquals("/opt/floci/tls/cert.crt",
                TlsConfigSource.toConfigPath("/opt/floci/tls/cert.crt", '/'));
        // a backslash is a legal filename character on unix and must survive
        assertEquals("/opt/floci/we\\ird.crt",
                TlsConfigSource.toConfigPath("/opt/floci/we\\ird.crt", '/'));
    }

    // --- emitted quarkus.http.ssl.* properties ---

    @Test
    void generatedCertificatePropertiesContainNoBackslashes() {
        TlsConfigSource source = new TlsConfigSource();

        String certFiles = source.getValue("quarkus.http.ssl.certificate.files");
        String keyFiles  = source.getValue("quarkus.http.ssl.certificate.key-files");

        assertNotNull(certFiles);
        assertNotNull(keyFiles);
        assertFalse(certFiles.contains("\\"), "cert path must not contain backslashes: " + certFiles);
        assertFalse(keyFiles.contains("\\"),  "key path must not contain backslashes: " + keyFiles);
        assertTrue(certFiles.endsWith("floci-az-selfsigned.crt"));
        assertTrue(keyFiles.endsWith("floci-az-selfsigned.key"));
        // the emitted form must still resolve to the generated files
        assertTrue(Files.exists(Path.of(certFiles)), "emitted cert path must exist: " + certFiles);
        assertTrue(Files.exists(Path.of(keyFiles)),  "emitted key path must exist: " + keyFiles);
    }

    @Test
    void userProvidedCertificatePropertiesContainNoBackslashes() throws Exception {
        Path cert = tempDir.resolve("user.crt");
        Path key  = tempDir.resolve("user.key");
        Files.writeString(cert, "dummy-cert");
        Files.writeString(key,  "dummy-key");
        System.setProperty("floci-az.tls.cert-path", cert.toString());
        System.setProperty("floci-az.tls.key-path",  key.toString());

        TlsConfigSource source = new TlsConfigSource();

        String certFiles = source.getValue("quarkus.http.ssl.certificate.files");
        String keyFiles  = source.getValue("quarkus.http.ssl.certificate.key-files");

        assertFalse(certFiles.contains("\\"), "cert path must not contain backslashes: " + certFiles);
        assertFalse(keyFiles.contains("\\"),  "key path must not contain backslashes: " + keyFiles);
        assertTrue(Files.exists(Path.of(certFiles)));
        assertTrue(Files.exists(Path.of(keyFiles)));
    }
}
