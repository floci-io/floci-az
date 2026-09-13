package io.floci.az.core.tls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TlsConfigSourceCertificateGenerationTest {

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
        System.clearProperty("floci-az.hostname");
        System.clearProperty("floci-az.base-url");
    }

    @Test
    void certificateIncludesFlociAzHostname() throws Exception {
        System.setProperty("floci-az.hostname", "floci-az");

        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-az-selfsigned.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");

        List<String> sans = extractSans(certFile);
        assertTrue(sans.contains("floci-az"), "SANs should include 'floci-az' from floci-az.hostname");
        assertTrue(sans.contains("localhost"), "SANs should include default 'localhost'");
    }

    @Test
    void certificateIncludesBaseUrlHostname() throws Exception {
        System.setProperty("floci-az.base-url", "https://myhost:4577");

        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-az-selfsigned.crt");
        List<String> sans = extractSans(certFile);
        assertTrue(sans.contains("myhost"), "SANs should include 'myhost' from floci-az.base-url");
        assertTrue(sans.contains("localhost"), "SANs should include default 'localhost'");
    }

    @Test
    void certificateIncludesIpFromBaseUrl() throws Exception {
        System.setProperty("floci-az.base-url", "https://192.168.1.100:4577");

        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-az-selfsigned.crt");
        List<String> sans = extractSans(certFile);
        assertTrue(sans.contains("192.168.1.100"), "SANs should include the IP from floci-az.base-url");
    }

    @Test
    void certificateWithDefaultConfigHasDefaultSans() throws Exception {
        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-az-selfsigned.crt");
        assertTrue(Files.exists(certFile));

        List<String> sans = extractSans(certFile);
        assertTrue(sans.contains("localhost"));
        assertTrue(sans.contains("127.0.0.1"));
        assertTrue(sans.contains("0.0.0.0"));
        assertTrue(sans.contains("host.docker.internal"),
            "SANs should include 'host.docker.internal' so function containers can reach floci-az on the host");
        assertTrue(sans.contains("*.managedhsm.azure.net"),
            "SANs should include '*.managedhsm.azure.net' for Managed-HSM-flavored vault URLs");
        assertEquals(9, sans.size(),
            "Default cert should have exactly 9 SANs (localhost, 127.0.0.1, 0.0.0.0, *.localhost, localhost.floci-az.io, *.localhost.floci-az.io, *.vault.azure.net, *.managedhsm.azure.net, host.docker.internal)");
    }

    @Test
    void certificateChainHasNonCaLeafSignedByCa() throws Exception {
        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-az-selfsigned.crt");
        Path caFile   = tempDir.resolve("tls/floci-az-selfsigned-ca.crt");

        // The .crt file is a 2-certificate chain (leaf immediately followed by CA), so parse it
        // with generateCertificates (plural), not generateCertificate.
        String pem = Files.readString(certFile);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> parsed = cf.generateCertificates(
                new ByteArrayInputStream(pem.getBytes()));
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : parsed) {
            certs.add((X509Certificate) c);
        }
        assertEquals(2, certs.size(), "Cert file should contain a leaf+CA chain (2 certificates)");

        X509Certificate leaf = certs.get(0);
        X509Certificate ca   = certs.get(1);

        // Leaf must be a genuine end-entity certificate: strict path validators (e.g.
        // rustls-platform-verifier's CaUsedAsEndEntity check) reject a CA-flagged cert in the
        // leaf position.
        assertEquals(-1, leaf.getBasicConstraints(), "Leaf must not be a CA");
        assertNotEquals(leaf.getIssuerX500Principal(), leaf.getSubjectX500Principal(),
                "Leaf must not be self-signed (issuer != subject)");

        // Second certificate is the signing CA.
        assertTrue(ca.getBasicConstraints() >= 0, "CA must be a CA (getBasicConstraints() >= 0)");
        assertEquals(ca.getSubjectX500Principal(), leaf.getIssuerX500Principal(),
                "Leaf issuer should be the CA's subject");

        // The leaf really is signed by the CA's key.
        leaf.verify(ca.getPublicKey());

        // The standalone CA file matches what GET /_floci/tls-cert serves as the trust anchor.
        assertTrue(Files.exists(caFile), "Standalone CA file should be written alongside the chain");
        assertEquals(Files.readString(caFile), TlsConfigSource.currentCertPem,
                "GET /_floci/tls-cert should serve the CA certificate, not the leaf");
    }

    @Test
    void duplicateHostnamesAreDeduplicatedInCert() throws Exception {
        System.setProperty("floci-az.hostname", "myhost");
        System.setProperty("floci-az.base-url", "http://myhost:4577");

        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-az-selfsigned.crt");
        List<String> sans = extractSans(certFile);
        long count = sans.stream().filter("myhost"::equals).count();
        assertEquals(1, count, "Duplicate hostnames must appear only once in the cert SANs");
    }

    @Test
    void metadataFileContainsCustomHostnames() throws Exception {
        System.setProperty("floci-az.hostname", "floci-az");
        System.setProperty("floci-az.base-url", "https://myhost:4577");

        new TlsConfigSource();

        Path metadataFile = tempDir.resolve("tls/floci-az-selfsigned.metadata.json");
        assertTrue(Files.exists(metadataFile), "Metadata file should be written after cert generation");

        String json = Files.readString(metadataFile);
        assertTrue(json.contains("floci-az"), "Metadata should contain 'floci-az'");
        assertTrue(json.contains("myhost"), "Metadata should contain 'myhost'");
        assertTrue(json.contains("localhost"), "Metadata should contain default 'localhost'");
    }

    private List<String> extractSans(Path certFile) throws Exception {
        String pem = Files.readString(certFile);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(pem.getBytes()));
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        if (sans == null) {
            return List.of();
        }
        return sans.stream()
                .filter(san -> san.size() >= 2)
                .map(san -> san.get(1).toString())
                .toList();
    }
}
