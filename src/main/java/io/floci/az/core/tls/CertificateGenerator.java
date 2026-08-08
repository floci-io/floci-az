package io.floci.az.core.tls;

import jakarta.enterprise.context.ApplicationScoped;
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
import org.jboss.logging.Logger;

import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

@ApplicationScoped
public class CertificateGenerator {

    private static final Logger LOG = Logger.getLogger(CertificateGenerator.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final Pattern IP_ADDRESS_PATTERN = Pattern.compile(
            "^\\[?([0-9a-fA-F:]+)]?$|^(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})$"
    );

    public record GeneratedCertificate(String certificatePem, String privateKeyPem) {}

    /**
     * Generates a genuinely self-signed certificate (issuer == subject, marked as a CA) suitable
     * for use as a <em>trust anchor</em>: a client that adds this certificate to its CA store can
     * verify a TLS connection that presents it. Used for floci-az's own HTTPS server certificate
     * so that containers making HTTPS calls back to floci-az can trust it once the certificate is
     * installed in their CA bundle.
     */
    public GeneratedCertificate generateCertificate(List<String> sans) {
        try {
            // Use the JDK's built-in RSA provider — BC's RSA JCA registration is
            // unreliable in GraalVM native image; BC is only needed for the X.509
            // certificate builder and signer which use BC's internal implementations.
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048, SECURE_RANDOM);
            KeyPair keyPair = keyGen.generateKeyPair();

            Instant now   = Instant.now();
            X500Name name = new X500Name("CN=floci-az");
            BigInteger serial = new BigInteger(128, SECURE_RANDOM);

            var certBuilder = new JcaX509v3CertificateBuilder(
                    name, serial,
                    Date.from(now), Date.from(now.plus(365, ChronoUnit.DAYS)),
                    name, keyPair.getPublic());

            GeneralName[] sanEntries = sans.stream()
                    .map(CertificateGenerator::toGeneralName)
                    .filter(gn -> gn != null)
                    .toArray(GeneralName[]::new);

            certBuilder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sanEntries));
            // A trust anchor must be a CA so clients accept it as one, and it needs keyCertSign
            // so it can act as its own issuer.
            certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            certBuilder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment | KeyUsage.keyCertSign));

            ContentSigner signer = new JcaContentSignerBuilder("SHA512WithRSA")
                    .build(keyPair.getPrivate());

            X509Certificate cert = new JcaX509CertificateConverter()
                    .getCertificate(certBuilder.build(signer));

            return new GeneratedCertificate(toPem(cert), toPem(keyPair.getPrivate()));

        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate self-signed TLS certificate", e);
        }
    }

    /**
     * Builds a self-signed X.509 certificate that wraps an <em>existing</em> keypair (unlike
     * {@link #generateCertificate(List)}, which generates its own). Used to publish the Entra
     * JWT signing key as {@code x5c}/{@code x5t} in a JWKS document.
     */
    public X509Certificate certify(KeyPair keyPair, String commonName) {
        try {
            Instant now = Instant.now();
            X500Name name = new X500Name("CN=" + commonName);
            BigInteger serial = new BigInteger(128, SECURE_RANDOM);

            var certBuilder = new JcaX509v3CertificateBuilder(
                    name, serial,
                    Date.from(now), Date.from(now.plus(3650, ChronoUnit.DAYS)),
                    name, keyPair.getPublic());

            certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));

            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                    .build(keyPair.getPrivate());

            return new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build self-signed certificate for " + commonName, e);
        }
    }

    private static GeneralName toGeneralName(String san) {
        try {
            if (san.startsWith("*.")) {
                return new GeneralName(GeneralName.dNSName, san);
            }
            if (isIpAddress(san)) {
                String addr = san.startsWith("[") && san.endsWith("]")
                        ? san.substring(1, san.length() - 1) : san;
                return new GeneralName(GeneralName.iPAddress,
                        new DEROctetString(InetAddress.getByName(addr).getAddress()));
            }
            return new GeneralName(GeneralName.dNSName, san);
        } catch (Exception e) {
            LOG.warnv("TLS: skipping invalid SAN entry '{0}': {1}", san, e.getMessage());
            return null;
        }
    }

    static boolean isIpAddress(String value) {
        if (value == null || value.isBlank() || value.startsWith("*")) {
            return false;
        }
        return IP_ADDRESS_PATTERN.matcher(value).matches();
    }

    private static String toPem(Object obj) throws Exception {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(obj);
        }
        return sw.toString();
    }
}
