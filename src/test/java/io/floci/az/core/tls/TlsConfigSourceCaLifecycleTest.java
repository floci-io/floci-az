package io.floci.az.core.tls;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clients trust the self-signed CA, not the server certificate, so the CA must outlive every reason
 * the server certificate is reissued, and be replaced only when it cannot issue the next one.
 */
class TlsConfigSourceCaLifecycleTest {

    @TempDir
    Path tempDir;

    private Path caFile;
    private Path caKeyFile;
    private Path certFile;

    @BeforeEach
    void setup() {
        System.setProperty("floci-az.tls.enabled", "true");
        System.setProperty("floci-az.tls.self-signed", "true");
        System.setProperty("floci-az.storage.persistent-path", tempDir.toString());
        caFile = tempDir.resolve("tls/" + TlsConfigSource.SELF_SIGNED_CA_NAME);
        caKeyFile = tempDir.resolve("tls/" + TlsConfigSource.SELF_SIGNED_CA_KEY_NAME);
        certFile = tempDir.resolve("tls/" + TlsConfigSource.SELF_SIGNED_CERT_NAME);
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("floci-az.tls.enabled");
        System.clearProperty("floci-az.tls.self-signed");
        System.clearProperty("floci-az.storage.persistent-path");
        System.clearProperty("floci-az.hostname");
        System.clearProperty("floci-az.docker.docker-host");
    }

    @Test
    void aHostnameChangeReissuesTheServerCertificateUnderTheSameCa() throws Exception {
        new TlsConfigSource();
        String caBefore = Files.readString(caFile);
        String leafBefore = Files.readString(certFile);

        System.setProperty("floci-az.hostname", "api.localhost");
        new TlsConfigSource();

        assertEquals(caBefore, Files.readString(caFile), "the CA clients trust must survive a hostname change");
        assertNotEquals(leafBefore, Files.readString(certFile));
        assertTrue(sans(leaf()).contains("api.localhost"));
        leaf().verify(ca().getPublicKey());
        assertEquals(caBefore, TlsConfigSource.currentCertPem);
    }

    @Test
    void aServerCertificateNearExpiryIsReissuedUnderTheSameCa() throws Exception {
        new TlsConfigSource();
        String caBefore = Files.readString(caFile);
        writeLeafExpiringIn(Duration.ofDays(5));

        new TlsConfigSource();

        assertEquals(caBefore, Files.readString(caFile));
        Instant notAfter = leaf().getNotAfter().toInstant();
        assertTrue(notAfter.isAfter(Instant.now().plus(Duration.ofDays(300))), "reissued leaf expires " + notAfter);
    }

    @Test
    void aHostnameOutsideTheCaConstraintsReplacesTheCa() throws Exception {
        System.setProperty("floci-az.hostname", "floci-az");
        new TlsConfigSource();
        String caBefore = Files.readString(caFile);

        System.setProperty("floci-az.hostname", "build-agent.internal");
        new TlsConfigSource();

        assertNotEquals(caBefore, Files.readString(caFile));
        assertTrue(CertificateGenerator.permits(ca(), "build-agent.internal"));
        leaf().verify(ca().getPublicKey());
    }

    @Test
    void aCaStillPermittingANameNoLongerServedIsReplaced() throws Exception {
        System.setProperty("floci-az.hostname", "build-agent.internal");
        new TlsConfigSource();
        String caBefore = Files.readString(caFile);

        System.clearProperty("floci-az.hostname");
        new TlsConfigSource();

        assertNotEquals(caBefore, Files.readString(caFile), "a trusted CA must not keep vouching for a dropped name");
        assertFalse(CertificateGenerator.permits(ca(), "build-agent.internal"));
        leaf().verify(ca().getPublicKey());
    }

    @Test
    void aCaWithoutNameConstraintsIsReplacedByOneWithThem() throws Exception {
        new TlsConfigSource();
        CertificateGenerator generator = new CertificateGenerator();
        CertificateGenerator.CertificateAuthority unconstrained = generator.generateAuthority();
        CertificateGenerator.GeneratedCertificate issued = generator.issue(unconstrained, List.of("localhost"));
        Files.writeString(caFile, issued.caPem());
        Files.writeString(caKeyFile, CertificateGenerator.privateKeyPem(unconstrained));
        Files.writeString(certFile, issued.chainPem());

        new TlsConfigSource();

        assertNotEquals(issued.caPem(), Files.readString(caFile));
        assertTrue(CertificateGenerator.hasNameConstraints(ca()));
    }

    @Test
    void aCaFromAnOlderFlociAzWithoutItsKeyIsKeptUntilTheCertificateMustBeReissued() throws Exception {
        new TlsConfigSource();
        Files.delete(caKeyFile);
        String caBefore = Files.readString(caFile);

        new TlsConfigSource();
        assertEquals(caBefore, Files.readString(caFile), "nothing changed, so clients keep the CA they trust");
        assertFalse(Files.exists(caKeyFile));

        System.setProperty("floci-az.hostname", "api.localhost");
        new TlsConfigSource();
        assertNotEquals(caBefore, Files.readString(caFile), "a CA without its key cannot issue the new certificate");
        assertTrue(Files.exists(caKeyFile));
        leaf().verify(ca().getPublicKey());
    }

    @Test
    void theCaMayIssueForEveryServerNameButNotForUnrelatedOnes() throws Exception {
        System.setProperty("floci-az.hostname", "floci-az");
        new TlsConfigSource();
        X509Certificate ca = ca();

        assertTrue(CertificateGenerator.hasNameConstraints(ca));
        for (String san : sans(leaf())) {
            assertTrue(CertificateGenerator.permits(ca, san), "server SAN outside the constraints: " + san);
        }
        for (String sidecar : List.of("cache-a.redis.cache.windows.net", "cache-a.redis.cache.usgovcloudapi.net",
                "host.docker.internal", "172.18.0.5")) {
            assertTrue(CertificateGenerator.permits(ca, sidecar), sidecar);
        }
        for (String unrelated : List.of("github.com", "login.microsoftonline.com", "evil.vault.azure.net.example",
                "8.8.8.8", "floci-az-redis-abc123")) {
            assertFalse(CertificateGenerator.permits(ca, unrelated), unrelated);
        }
    }

    @Test
    void aDockerDaemonReachedByNameIsInsideTheConstraints() throws Exception {
        System.setProperty("floci-az.docker.docker-host", "tcp://docker-host.lan:2375");

        new TlsConfigSource();

        assertTrue(CertificateGenerator.permits(ca(), "docker-host.lan"));
    }

    /** Python 3.13+ verifies in strict mode, which rejects a leaf without an Authority Key Identifier. */
    @Test
    void theServerCertificateCarriesAnAuthorityKeyIdentifier() throws Exception {
        new TlsConfigSource();

        assertNotNull(leaf().getExtensionValue(Extension.authorityKeyIdentifier.getId()));
    }

    private X509Certificate ca() throws Exception {
        return parse(Files.readString(caFile));
    }

    private X509Certificate leaf() throws Exception {
        return parse(Files.readString(certFile));
    }

    private static X509Certificate parse(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> sans(X509Certificate certificate) throws Exception {
        return certificate.getSubjectAlternativeNames().stream().map(entry -> String.valueOf(entry.get(1))).toList();
    }

    /** Replaces the server certificate with one for the same names, issued by the CA on disk, expiring soon. */
    private void writeLeafExpiringIn(Duration validity) throws Exception {
        CertificateGenerator.CertificateAuthority authority =
                CertificateGenerator.readAuthority(Files.readString(caFile), Files.readString(caKeyFile));
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        KeyPair pair = keys.generateKeyPair();
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                X500Name.getInstance(authority.certificate().getSubjectX500Principal().getEncoded()),
                BigInteger.ONE, Date.from(now.minus(Duration.ofDays(360))), Date.from(now.plus(validity)),
                new X500Name("CN=floci-az"), pair.getPublic());
        X509Certificate expiring = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256WithRSA").build(authority.privateKey())));
        StringWriter pem = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(pem)) {
            writer.writeObject(expiring);
            writer.writeObject(authority.certificate());
        }
        Files.writeString(certFile, pem.toString());
    }
}
