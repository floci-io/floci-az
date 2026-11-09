package io.floci.az.core.tls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code floci-az.tls.public-hosts} adds Azure's own hosts to the certificate, and only when it is on. */
class TlsPublicHostsTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void enableTls() {
        System.setProperty("floci-az.tls.enabled", "true");
        System.setProperty("floci-az.tls.self-signed", "true");
        System.setProperty("floci-az.storage.persistent-path", tempDir.toString());
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("floci-az.tls.enabled");
        System.clearProperty("floci-az.tls.self-signed");
        System.clearProperty("floci-az.storage.persistent-path");
        System.clearProperty("floci-az.tls.public-hosts");
    }

    @Test
    void thePublicHostsAreThePublicCloudsArmSignInAndGraphHosts() {
        assertEquals(List.of("management.azure.com", "management.core.windows.net", "login.microsoftonline.com",
            "graph.microsoft.com"), TlsConfigSource.PUBLIC_HOSTS);
    }

    private List<String> sans() throws Exception {
        new TlsConfigSource();
        Path certificate = tempDir.resolve("tls/" + TlsConfigSource.SELF_SIGNED_CERT_NAME);
        try (InputStream in = Files.newInputStream(certificate)) {
            X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            List<String> names = new ArrayList<>();
            Collection<List<?>> alternatives = parsed.getSubjectAlternativeNames();
            for (List<?> alternative : alternatives) {
                names.add(String.valueOf(alternative.get(1)));
            }
            return names;
        }
    }

    @Test
    void offByDefaultTheCertificateNamesNoneOfAzuresOwnHosts() throws Exception {
        List<String> names = sans();

        TlsConfigSource.PUBLIC_HOSTS.forEach(host -> assertFalse(names.contains(host), host));
        assertTrue(names.contains("*.vault.azure.net"), names.toString());
    }

    @Test
    void onTheCertificateNamesThemAndKeepsTheRest() throws Exception {
        System.setProperty("floci-az.tls.public-hosts", "true");

        List<String> names = sans();

        TlsConfigSource.PUBLIC_HOSTS.forEach(host -> assertTrue(names.contains(host), host));
        assertTrue(names.contains("*.vault.azure.net"), names.toString());
    }
}
