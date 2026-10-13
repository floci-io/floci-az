package io.floci.az.core.tls;

import io.floci.az.config.EmulatorConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A sidecar serving TLS must be trusted the way the emulator's HTTPS endpoint is: under the CA that
 * {@code GET /_floci/tls-cert} serves when floci-az TLS is on, and for the host names clients use.
 */
class SidecarCertificatesTest {

    @TempDir
    Path tempDir;

    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());
        when(config.tls().certPath()).thenReturn(Optional.empty());
        when(config.tls().keyPath()).thenReturn(Optional.empty());
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("floci-az.tls.enabled");
        System.clearProperty("floci-az.tls.self-signed");
        System.clearProperty("floci-az.storage.persistent-path");
        System.clearProperty("floci-az.services.redis.tls-enabled");
    }

    @Test
    void withTlsOffTheLeafCarriesTheSidecarNamesAndEveryLeafSharesOneCa() throws Exception {
        when(config.tls().enabled()).thenReturn(false);
        SidecarCertificates certificates = new SidecarCertificates(config, new CertificateGenerator());

        SidecarCertificates.ServerCertificate first = certificates.issue(List.of("floci-az-redis-a"));
        SidecarCertificates.ServerCertificate second = certificates.issue(List.of("floci-az-redis-b"));

        X509Certificate leaf = chain(first.chainPem()).getFirst();
        assertTrue(sans(leaf).containsAll(List.of("floci-az-redis-a", "localhost", "127.0.0.1",
                "host.docker.internal")), "SANs: " + sans(leaf));
        assertDoesNotThrow(() -> leaf.verify(single(first.caPem()).getPublicKey()));
        assertEquals(first.caPem(), second.caPem());
        assertFalse(Files.exists(tempDir.resolve("tls")), "nothing is written while floci-az TLS is off");
    }

    @Test
    void withRedisTlsOffTheEmulatorCaKeyIsNotWritten() {
        enableSelfSignedTls();

        assertTrue(Files.exists(tempDir.resolve("tls/floci-az-selfsigned-ca.crt")));
        assertFalse(Files.exists(tempDir.resolve("tls/floci-az-selfsigned-ca.key")),
                "only a sidecar serving TLS needs the CA key on disk");
    }

    @Test
    void withSelfSignedTlsTheLeafChainsToTheCaTheHttpsEndpointServes() throws Exception {
        System.setProperty("floci-az.services.redis.tls-enabled", "true");
        enableSelfSignedTls();
        Path caKey = tempDir.resolve("tls/floci-az-selfsigned-ca.key");
        assertTrue(Files.exists(caKey), "the emulator CA key is kept for sidecar leaves");

        SidecarCertificates.ServerCertificate issued =
                new SidecarCertificates(config, new CertificateGenerator()).issue(List.of("floci-az-redis-a"));

        String servedAnchor = TlsConfigSource.currentCertPem;
        assertEquals(servedAnchor, issued.caPem());
        X509Certificate leaf = chain(issued.chainPem()).getFirst();
        assertDoesNotThrow(() -> leaf.verify(single(servedAnchor).getPublicKey()));
        assertTrue(sans(leaf).contains("floci-az-redis-a"));
        assertNotEquals(Files.readString(tempDir.resolve("tls/floci-az-selfsigned.key")), issued.privateKeyPem());
    }

    @Test
    void withSelfSignedTlsButNoCaKeyTheEmulatorCertificateIsServedAsIs() throws Exception {
        // Generated while Redis TLS was off, so the CA key was not kept.
        enableSelfSignedTls();

        SidecarCertificates.ServerCertificate issued =
                new SidecarCertificates(config, new CertificateGenerator()).issue(List.of("floci-az-redis-a"));

        assertEquals(Files.readString(tempDir.resolve("tls/floci-az-selfsigned.crt")), issued.chainPem());
        assertEquals(Files.readString(tempDir.resolve("tls/floci-az-selfsigned.key")), issued.privateKeyPem());
        assertEquals(TlsConfigSource.currentCertPem, issued.caPem());
    }

    /**
     * Redis TLS on, then a regeneration with it off (what a hostname change causes), then on again:
     * the key of the replaced CA must not survive to sign leaves under the new one.
     */
    @Test
    void aCaKeyFromAnEarlierRunIsRemovedWhenTheCertificateIsRegeneratedWithoutIt() throws Exception {
        System.setProperty("floci-az.services.redis.tls-enabled", "true");
        enableSelfSignedTls();
        Path caKey = tempDir.resolve("tls/" + TlsConfigSource.SELF_SIGNED_CA_KEY_NAME);
        assertTrue(Files.exists(caKey));

        System.clearProperty("floci-az.services.redis.tls-enabled");
        Files.delete(tempDir.resolve("tls/" + TlsConfigSource.SELF_SIGNED_METADATA_NAME));
        new TlsConfigSource();
        assertFalse(Files.exists(caKey), "the replaced CA's key must not stay next to the new CA");

        System.setProperty("floci-az.services.redis.tls-enabled", "true");
        new TlsConfigSource();
        SidecarCertificates.ServerCertificate issued =
                new SidecarCertificates(config, new CertificateGenerator()).issue(List.of("floci-az-redis-a"));

        String servedAnchor = TlsConfigSource.currentCertPem;
        assertEquals(servedAnchor, issued.caPem());
        X509Certificate leaf = chain(issued.chainPem()).getFirst();
        assertDoesNotThrow(() -> leaf.verify(single(servedAnchor).getPublicKey()));
    }

    @Test
    void aCaKeyThatDoesNotBelongToTheCaIsNotUsedToSignLeaves() throws Exception {
        System.setProperty("floci-az.services.redis.tls-enabled", "true");
        enableSelfSignedTls();
        CertificateGenerator generator = new CertificateGenerator();
        Files.writeString(tempDir.resolve("tls/" + TlsConfigSource.SELF_SIGNED_CA_KEY_NAME),
                CertificateGenerator.privateKeyPem(generator.generateAuthority()));

        SidecarCertificates.ServerCertificate issued =
                new SidecarCertificates(config, generator).issue(List.of("floci-az-redis-a"));

        assertEquals(Files.readString(tempDir.resolve("tls/floci-az-selfsigned.crt")), issued.chainPem());
        X509Certificate leaf = chain(issued.chainPem()).getFirst();
        assertDoesNotThrow(() -> leaf.verify(single(TlsConfigSource.currentCertPem).getPublicKey()));
    }

    @Test
    void theCaKeyIsReadableByItsOwnerOnly() throws Exception {
        System.setProperty("floci-az.services.redis.tls-enabled", "true");
        enableSelfSignedTls();
        Path caKey = tempDir.resolve("tls/" + TlsConfigSource.SELF_SIGNED_CA_KEY_NAME);
        assumeTrue(Files.getFileStore(caKey).supportsFileAttributeView("posix"));

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(caKey)));
    }

    @Test
    void withAUserProvidedCertificateThatCertificateIsServed() throws Exception {
        CertificateGenerator.GeneratedCertificate user =
                new CertificateGenerator().generateCertificate(List.of("redis.example.test"));
        Path cert = Files.writeString(tempDir.resolve("user.crt"), user.chainPem());
        Path key = Files.writeString(tempDir.resolve("user.key"), user.privateKeyPem());
        when(config.tls().enabled()).thenReturn(true);
        when(config.tls().certPath()).thenReturn(Optional.of(cert.toString()));
        when(config.tls().keyPath()).thenReturn(Optional.of(key.toString()));

        SidecarCertificates.ServerCertificate issued =
                new SidecarCertificates(config, new CertificateGenerator()).issue(List.of("floci-az-redis-a"));

        assertEquals(user.chainPem(), issued.chainPem());
        assertEquals(user.privateKeyPem(), issued.privateKeyPem());
    }

    @Test
    void aCaReadBackFromPemIssuesLeavesThatVerifyAgainstIt() throws Exception {
        CertificateGenerator generator = new CertificateGenerator();
        CertificateGenerator.CertificateAuthority authority = generator.generateAuthority();
        String caPem = generator.issue(authority, List.of("localhost")).caPem();

        CertificateGenerator.CertificateAuthority readBack =
                CertificateGenerator.readAuthority(caPem, CertificateGenerator.privateKeyPem(authority));
        X509Certificate leaf = chain(generator.issue(readBack, List.of("localhost")).chainPem()).getFirst();

        assertDoesNotThrow(() -> leaf.verify(authority.certificate().getPublicKey()));
    }

    private void enableSelfSignedTls() {
        System.setProperty("floci-az.tls.enabled", "true");
        System.setProperty("floci-az.tls.self-signed", "true");
        System.setProperty("floci-az.storage.persistent-path", tempDir.toString());
        new TlsConfigSource();
        when(config.tls().enabled()).thenReturn(true);
    }

    private static List<X509Certificate> chain(String pem) throws Exception {
        Collection<? extends Certificate> parsed = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        List<X509Certificate> certificates = new ArrayList<>();
        for (Certificate certificate : parsed) {
            certificates.add((X509Certificate) certificate);
        }
        return certificates;
    }

    private static X509Certificate single(String pem) throws Exception {
        return chain(pem).getFirst();
    }

    private static List<String> sans(X509Certificate certificate) throws Exception {
        List<String> names = new ArrayList<>();
        for (List<?> entry : certificate.getSubjectAlternativeNames()) {
            names.add(String.valueOf(entry.get(1)));
        }
        return names;
    }
}
