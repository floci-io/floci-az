package io.floci.az.core.tls;

import io.floci.az.config.EmulatorConfig;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
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
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A CA the user already trusts (mkcert's root, a team CA) issues floci-az's server certificate, so
 * clients need no extra trust step. floci-az only reads that CA's key and never copies it.
 */
class TlsConfigSourceUserCaTest {

    @TempDir
    Path tempDir;

    private Path caCertFile;
    private Path caKeyFile;

    @BeforeEach
    void setup() {
        System.setProperty("floci-az.tls.enabled", "true");
        System.setProperty("floci-az.storage.persistent-path", tempDir.resolve("data").toString());
        caCertFile = tempDir.resolve("rootCA.pem");
        caKeyFile = tempDir.resolve("rootCA-key.pem");
    }

    @AfterEach
    void cleanup() {
        for (String key : List.of("floci-az.tls.enabled", "floci-az.storage.persistent-path", "floci-az.hostname",
                "floci-az.tls.ca-cert-path", "floci-az.tls.ca-key-path", "floci-az.tls.cert-path",
                "floci-az.tls.key-path")) {
            System.clearProperty(key);
        }
        TlsConfigSource.currentCertPem = null;
    }

    @Test
    void anMkcertStyleCaIssuesTheServerCertificateAndIsTheTrustAnchor() throws Exception {
        KeyPair caKeys = keyPair("RSA", 3072);
        writeCa(caKeys, true, Duration.ofDays(3650));
        writePkcs8Key(caKeys);
        System.setProperty("floci-az.hostname", "floci-az");

        new TlsConfigSource();

        X509Certificate ca = parse(Files.readString(caCertFile));
        X509Certificate leaf = parse(Files.readString(issuedCert()));
        leaf.verify(ca.getPublicKey());
        assertTrue(sans(leaf).containsAll(List.of("localhost", "127.0.0.1", "*.vault.azure.net", "floci-az")),
                "SANs: " + sans(leaf));
        assertEquals(Files.readString(caCertFile), TlsConfigSource.currentCertPem, "/_floci/tls-cert serves the user's CA");
        assertFalse(Files.exists(selfSignedCa()), "no CA of floci-az's own is generated");
    }

    @Test
    void theUsersCaKeyIsNeverCopied() throws Exception {
        KeyPair caKeys = keyPair("RSA", 2048);
        writeCa(caKeys, true, Duration.ofDays(3650));
        writePkcs8Key(caKeys);

        new TlsConfigSource();

        String caKey = Files.readString(caKeyFile);
        try (Stream<Path> files = Files.walk(tempDir.resolve("data"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(file).contains(caKey.strip()), "CA key copied into " + file);
            }
        }
    }

    @Test
    void anEcCaWithASec1KeyIssuesToo() throws Exception {
        // BouncyCastle keeps the curve inside the key, as openssl ecparam -genkey writes it; a JDK key
        // carries it only in the PKCS#8 wrapper, so its SEC1 form would lose it.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair caKeys = generator.generateKeyPair();
        writeCa(caKeys, true, Duration.ofDays(3650));
        writeTraditionalKey(caKeys);

        new TlsConfigSource();

        parse(Files.readString(issuedCert())).verify(caKeys.getPublic());
    }

    @Test
    void anEd25519CaIssuesToo() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair caKeys = generator.generateKeyPair();
        writeCa(caKeys, true, Duration.ofDays(3650));
        writePkcs8Key(caKeys);

        new TlsConfigSource();

        parse(Files.readString(issuedCert())).verify(caKeys.getPublic());
    }

    @Test
    void theServerCertificateNeverOutlivesTheCa() throws Exception {
        KeyPair caKeys = keyPair("RSA", 2048);
        X509Certificate ca = writeCa(caKeys, true, Duration.ofDays(20));
        writePkcs8Key(caKeys);

        new TlsConfigSource();

        X509Certificate leaf = parse(Files.readString(issuedCert()));
        assertFalse(leaf.getNotAfter().after(ca.getNotAfter()), "leaf " + leaf.getNotAfter() + ", CA " + ca.getNotAfter());
    }

    @Test
    void aUserCertificateTakesPrecedenceOverTheCaPaths() throws Exception {
        KeyPair caKeys = keyPair("RSA", 2048);
        writeCa(caKeys, true, Duration.ofDays(3650));
        writePkcs8Key(caKeys);
        CertificateGenerator.GeneratedCertificate own = new CertificateGenerator().generateCertificate(List.of("localhost"));
        Path cert = Files.writeString(tempDir.resolve("own.crt"), own.chainPem());
        Path key = Files.writeString(tempDir.resolve("own.key"), own.privateKeyPem());
        System.setProperty("floci-az.tls.cert-path", cert.toString());
        System.setProperty("floci-az.tls.key-path", key.toString());

        new TlsConfigSource();

        assertEquals(own.chainPem(), TlsConfigSource.currentCertPem);
        assertFalse(Files.exists(issuedCert()));
    }

    @Test
    void onlyOneOfTheTwoCaPathsIsAConfigurationError() throws Exception {
        writeCa(keyPair("RSA", 2048), true, Duration.ofDays(3650));
        System.clearProperty("floci-az.tls.ca-key-path");

        IllegalStateException error = assertThrows(IllegalStateException.class, TlsConfigSource::new);
        assertTrue(error.getMessage().contains("FLOCI_AZ_TLS_CA_KEY_PATH"), error.getMessage());
    }

    @Test
    void aMissingKeyFileIsAConfigurationError() throws Exception {
        writeCa(keyPair("RSA", 2048), true, Duration.ofDays(3650));
        System.setProperty("floci-az.tls.ca-key-path", tempDir.resolve("absent.pem").toString());

        IllegalStateException error = assertThrows(IllegalStateException.class, TlsConfigSource::new);
        assertTrue(error.getMessage().contains("absent.pem"), error.getMessage());
    }

    @Test
    void aKeyOfAnotherCaIsAConfigurationError() throws Exception {
        writeCa(keyPair("RSA", 2048), true, Duration.ofDays(3650));
        writePkcs8Key(keyPair("RSA", 2048));

        IllegalStateException error = assertThrows(IllegalStateException.class, TlsConfigSource::new);
        assertTrue(error.getMessage().contains("is not the private key of"), error.getMessage());
    }

    @Test
    void aCertificateThatIsNotACaIsAConfigurationError() throws Exception {
        KeyPair keys = keyPair("RSA", 2048);
        writeCa(keys, false, Duration.ofDays(3650));
        writePkcs8Key(keys);

        IllegalStateException error = assertThrows(IllegalStateException.class, TlsConfigSource::new);
        assertTrue(error.getMessage().contains("is not a CA certificate"), error.getMessage());
    }

    @Test
    void sidecarCertificatesAreIssuedFromTheUsersCaToo() throws Exception {
        KeyPair caKeys = keyPair("RSA", 2048);
        writeCa(caKeys, true, Duration.ofDays(3650));
        writePkcs8Key(caKeys);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(true);
        when(config.tls().certPath()).thenReturn(Optional.empty());
        when(config.tls().keyPath()).thenReturn(Optional.empty());
        when(config.tls().caCertPath()).thenReturn(Optional.of(caCertFile.toString()));
        when(config.tls().caKeyPath()).thenReturn(Optional.of(caKeyFile.toString()));
        when(config.storage().persistentPath()).thenReturn(tempDir.resolve("data").toString());

        SidecarCertificates.ServerCertificate issued = new SidecarCertificates(config, new CertificateGenerator())
                .issue(List.of("cache.redis.cache.windows.net"));

        X509Certificate leaf = parse(issued.chainPem());
        leaf.verify(caKeys.getPublic());
        assertTrue(sans(leaf).contains("cache.redis.cache.windows.net"));
        assertEquals(Files.readString(caCertFile).strip(), issued.caPem().strip());
    }

    private Path issuedCert() {
        return tempDir.resolve("data/tls/" + TlsConfigSource.USER_CA_ISSUED_CERT_NAME);
    }

    private Path selfSignedCa() {
        return tempDir.resolve("data/tls/" + TlsConfigSource.SELF_SIGNED_CA_NAME);
    }

    private static KeyPair keyPair(String algorithm, int size) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        generator.initialize(size);
        return generator.generateKeyPair();
    }

    /** A root shaped like mkcert's: self-signed, CA basic constraint, keyCertSign; no name constraints. */
    private X509Certificate writeCa(KeyPair keys, boolean isCa, Duration validity) throws Exception {
        Instant now = Instant.now();
        X500Name name = new X500Name("O=mkcert development CA,CN=mkcert test");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, BigInteger.TEN,
                Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(validity)), name, keys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCa));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.digitalSignature));
        String algorithm = CertificateGenerator.signatureAlgorithm(keys.getPrivate()).replace("SHA512", "SHA256");
        X509Certificate certificate = new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder(algorithm).build(keys.getPrivate())));
        writePem(caCertFile, certificate);
        System.setProperty("floci-az.tls.ca-cert-path", caCertFile.toString());
        return certificate;
    }

    /** mkcert writes its root key as PKCS#8 ("BEGIN PRIVATE KEY"). */
    private void writePkcs8Key(KeyPair keys) throws Exception {
        writePem(caKeyFile, new JcaPKCS8Generator(keys.getPrivate(), null));
        System.setProperty("floci-az.tls.ca-key-path", caKeyFile.toString());
    }

    /** OpenSSL's traditional form: SEC1 ("BEGIN EC PRIVATE KEY") for EC, PKCS#1 for RSA. */
    private void writeTraditionalKey(KeyPair keys) throws Exception {
        writePem(caKeyFile, keys);
        System.setProperty("floci-az.tls.ca-key-path", caKeyFile.toString());
    }

    private static void writePem(Path file, Object value) throws Exception {
        StringWriter pem = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(pem)) {
            writer.writeObject(value);
        }
        Files.writeString(file, pem.toString());
    }

    private static X509Certificate parse(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> sans(X509Certificate certificate) throws Exception {
        return certificate.getSubjectAlternativeNames().stream().map(entry -> String.valueOf(entry.get(1))).toList();
    }
}
