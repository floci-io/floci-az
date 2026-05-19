package io.floci.az.core.tls;

import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A MicroProfile {@link ConfigSource} that dynamically provides Quarkus TLS/SSL
 * configuration when {@code floci-az.tls.enabled=true}.
 *
 * <p>
 * This runs <em>before</em> the Quarkus HTTP server starts, which is critical
 * because Quarkus reads {@code quarkus.http.ssl.*} properties during server
 * initialization. A CDI {@code @Startup} bean or {@code StartupEvent} observer
 * would be too late.
 *
 * <p>
 * When TLS is enabled with self-signed mode, the certificate is generated
 * using BouncyCastle and persisted under {@code {storage.persistent-path}/tls/}
 * for reuse across restarts.
 *
 * <p>
 * Both HTTP and HTTPS are served simultaneously via a {@link TlsProxyServer}
 * that listens on the public port and routes by protocol detection.
 */
public class TlsConfigSource implements ConfigSource {

    private static final Logger LOG = Logger.getLogger(TlsConfigSource.class);

    private static final String SELF_SIGNED_CERT_NAME = "floci-az-selfsigned.crt";
    private static final String SELF_SIGNED_KEY_NAME = "floci-az-selfsigned.key";
    private static final String TLS_DIR = "tls";

    // Internal ports used when TLS proxy is active
    static final int HTTP_INTERNAL_PORT = 4570;
    static final int HTTPS_INTERNAL_PORT = 4571;

    private final Map<String, String> properties = new HashMap<>();

    public TlsConfigSource() {
        String enabled = resolveProperty("floci-az.tls.enabled", "false");
        if (!"true".equalsIgnoreCase(enabled)) {
            LOG.debug("TLS disabled — TlsConfigSource inactive");
            return;
        }

        String certPath = resolveProperty("floci-az.tls.cert-path", "");
        String keyPath = resolveProperty("floci-az.tls.key-path", "");
        String selfSigned = resolveProperty("floci-az.tls.self-signed", "true");
        String persistentPath = resolveProperty("floci-az.storage.persistent-path", "./data");

        if (!certPath.isBlank() && !keyPath.isBlank()) {
            validateFileExists(certPath, "TLS certificate");
            validateFileExists(keyPath, "TLS private key");
            LOG.infov("TLS: using user-provided certificate: {0}", certPath);
        } else if ("true".equalsIgnoreCase(selfSigned)) {
            Path tlsDir = Path.of(persistentPath, TLS_DIR);
            Path certFile = tlsDir.resolve(SELF_SIGNED_CERT_NAME);
            Path keyFile = tlsDir.resolve(SELF_SIGNED_KEY_NAME);

            if (Files.exists(certFile) && Files.exists(keyFile)) {
                LOG.infov("TLS: reusing existing self-signed certificate: {0}", certFile);
            } else {
                generateSelfSignedCert(tlsDir, certFile, keyFile);
            }

            certPath = certFile.toAbsolutePath().toString();
            keyPath = keyFile.toAbsolutePath().toString();
        } else {
            throw new IllegalStateException(
                    "TLS enabled but no certificate provided and self-signed generation disabled. "
                            + "Set FLOCI_AZ_TLS_CERT_PATH + FLOCI_AZ_TLS_KEY_PATH, or enable FLOCI_AZ_TLS_SELF_SIGNED.");
        }

        properties.put("quarkus.http.ssl.certificate.files", certPath);
        properties.put("quarkus.http.ssl.certificate.key-files", keyPath);
        // When TLS is enabled, Quarkus HTTP and HTTPS run on internal ports.
        // TlsProxyServer listens on the public floci-az port and routes by protocol.
        properties.put("quarkus.http.insecure-requests", "enabled");
        properties.put("quarkus.http.host", "127.0.0.1");
        properties.put("quarkus.http.port", String.valueOf(HTTP_INTERNAL_PORT));
        properties.put("quarkus.http.ssl-port", String.valueOf(HTTPS_INTERNAL_PORT));

        LOG.infov("TLS: HTTPS enabled — proxy will listen on port {0} (HTTP+HTTPS), cert={1}",
                resolveProperty("floci-az.port", "4577"), certPath);
    }

    @Override
    public int getOrdinal() {
        // Higher than application.yml (250) so TLS properties take precedence
        return 300;
    }

    @Override
    public Set<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
    }

    @Override
    public String getName() {
        return "FlociAzTlsConfigSource";
    }

    /**
     * Resolves a property from system properties or environment variables.
     * Environment variable names follow the MicroProfile convention:
     * {@code floci-az.tls.enabled} → {@code FLOCI_AZ_TLS_ENABLED}.
     */
    static String resolveProperty(String key, String defaultValue) {
        // 1. System property (highest priority)
        String value = System.getProperty(key);
        if (value != null && !value.isBlank())
            return value;

        // 2. Environment variable (dots and dashes → underscores, uppercase)
        String envKey = key.replace('.', '_').replace('-', '_').toUpperCase();
        value = System.getenv(envKey);
        if (value != null && !value.isBlank())
            return value;

        return defaultValue;
    }

    private void generateSelfSignedCert(Path tlsDir, Path certFile, Path keyFile) {
        try {
            Files.createDirectories(tlsDir);

            // BouncyCastle may not be registered yet — ConfigSource runs before CDI
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }

            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
            keyGen.initialize(2048, new SecureRandom());
            KeyPair keyPair = keyGen.generateKeyPair();

            Instant now = Instant.now();
            X500Name name = new X500Name("CN=floci-az");
            BigInteger serial = new BigInteger(128, new SecureRandom());

            var certBuilder = new JcaX509v3CertificateBuilder(
                    name, serial,
                    Date.from(now), Date.from(now.plus(3650, ChronoUnit.DAYS)),
                    name, keyPair.getPublic());

            GeneralName[] sans = {
                    new GeneralName(GeneralName.dNSName, "localhost"),
                    new GeneralName(GeneralName.dNSName, "*.localhost"),
                    new GeneralName(GeneralName.dNSName, "localhost.floci-az.io"),
                    new GeneralName(GeneralName.dNSName, "*.localhost.floci-az.io"),
                    new GeneralName(GeneralName.iPAddress,
                            new DEROctetString(InetAddress.getByName("127.0.0.1").getAddress())),
                    new GeneralName(GeneralName.iPAddress,
                            new DEROctetString(InetAddress.getByName("0.0.0.0").getAddress())),
            };
            certBuilder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans));
            certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            certBuilder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(keyPair.getPrivate());

            X509Certificate cert = new JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(certBuilder.build(signer));

            // Write PEM cert
            try (JcaPEMWriter pw = new JcaPEMWriter(Files.newBufferedWriter(certFile))) {
                pw.writeObject(cert);
            }
            // Write PEM private key
            try (JcaPEMWriter pw = new JcaPEMWriter(Files.newBufferedWriter(keyFile))) {
                pw.writeObject(keyPair.getPrivate());
            }

            LOG.infov("TLS: generated self-signed certificate: {0}", certFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write self-signed TLS certificate", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate self-signed TLS certificate", e);
        }
    }

    private static void validateFileExists(String path, String description) {
        if (!Files.isReadable(Path.of(path))) {
            throw new IllegalStateException(
                    description + " file not found or not readable: " + path);
        }
    }
}
