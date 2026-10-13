package io.floci.az.core.tls;

import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.jboss.logging.Logger;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
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

    /**
     * @param chainPem      leaf followed by CA, in PEM (Quarkus "chain file" format)
     * @param caPem         the CA certificate alone, in PEM (the client trust anchor)
     * @param privateKeyPem the leaf's private key, in PEM
     */
    public record GeneratedCertificate(String chainPem, String caPem, String privateKeyPem) {}

    /** A CA that can issue leaves: its certificate and the private key that signs them. */
    public record CertificateAuthority(X509Certificate certificate, PrivateKey privateKey) {

        /** Whether the private key is the one the certificate was issued for. */
        public boolean keyMatchesCertificate() {
            return privateKey instanceof RSAPrivateKey key
                    && certificate.getPublicKey() instanceof RSAPublicKey publicKey
                    && key.getModulus().equals(publicKey.getModulus());
        }
    }

    /**
     * Generates a CA and a leaf it signs, in one step (see {@link #issue}). The CA's private key is
     * discarded, so no further leaf can chain to the returned CA.
     */
    public GeneratedCertificate generateCertificate(List<String> sans) {
        return issue(generateAuthority(), sans);
    }

    /** Generates a self-signed CA (CN=floci-az-ca, valid for ten years) that can issue leaves. */
    public CertificateAuthority generateAuthority() {
        try {
            // Use the JDK's built-in RSA provider: BC's RSA JCA registration is
            // unreliable in GraalVM native image; BC is only needed for the X.509
            // certificate builder and signer which use BC's internal implementations.
            KeyPair caKeyPair = newKeyPair();
            JcaX509ExtensionUtils extensionUtils = new JcaX509ExtensionUtils();
            Instant now = Instant.now();
            X500Name caName = new X500Name("CN=floci-az-ca");
            BigInteger caSerial = new BigInteger(128, SECURE_RANDOM);

            JcaX509v3CertificateBuilder caBuilder = new JcaX509v3CertificateBuilder(
                    caName, caSerial,
                    Date.from(now), Date.from(now.plus(3650, ChronoUnit.DAYS)),
                    caName, caKeyPair.getPublic());
            caBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            caBuilder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            caBuilder.addExtension(Extension.subjectKeyIdentifier, false,
                    extensionUtils.createSubjectKeyIdentifier(caKeyPair.getPublic()));

            ContentSigner caSigner = new JcaContentSignerBuilder("SHA512WithRSA")
                    .build(caKeyPair.getPrivate());
            X509Certificate caCert = new JcaX509CertificateConverter()
                    .getCertificate(caBuilder.build(caSigner));
            return new CertificateAuthority(caCert, caKeyPair.getPrivate());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TLS certificate authority", e);
        }
    }

    /**
     * Issues a non-CA leaf (CN=floci-az, valid for one year) signed by {@code ca}, carrying the SANs
     * and serverAuth EKU. The leaf must not be a CA, because strict validators (e.g. rustls) reject
     * a CA-flagged certificate in the end-entity position.
     */
    public GeneratedCertificate issue(CertificateAuthority ca, List<String> sans) {
        try {
            KeyPair leafKeyPair = newKeyPair();
            JcaX509ExtensionUtils extensionUtils = new JcaX509ExtensionUtils();
            Instant now = Instant.now();
            X500Name caName = X500Name.getInstance(ca.certificate().getSubjectX500Principal().getEncoded());
            X500Name leafName = new X500Name("CN=floci-az");
            BigInteger leafSerial = new BigInteger(128, SECURE_RANDOM);

            JcaX509v3CertificateBuilder leafBuilder = new JcaX509v3CertificateBuilder(
                    caName, leafSerial,
                    Date.from(now), Date.from(now.plus(365, ChronoUnit.DAYS)),
                    leafName, leafKeyPair.getPublic());

            GeneralName[] sanEntries = sans.stream()
                    .map(CertificateGenerator::toGeneralName)
                    .filter(gn -> gn != null)
                    .toArray(GeneralName[]::new);
            leafBuilder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sanEntries));
            leafBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            leafBuilder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            leafBuilder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            leafBuilder.addExtension(Extension.authorityKeyIdentifier, false,
                    extensionUtils.createAuthorityKeyIdentifier(ca.certificate().getPublicKey()));
            leafBuilder.addExtension(Extension.subjectKeyIdentifier, false,
                    extensionUtils.createSubjectKeyIdentifier(leafKeyPair.getPublic()));

            ContentSigner leafSigner = new JcaContentSignerBuilder("SHA512WithRSA")
                    .build(ca.privateKey());
            X509Certificate leafCert = new JcaX509CertificateConverter()
                    .getCertificate(leafBuilder.build(leafSigner));

            return new GeneratedCertificate(
                    toPem(leafCert) + toPem(ca.certificate()),
                    toPem(ca.certificate()),
                    toPem(leafKeyPair.getPrivate()));

        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TLS certificate chain", e);
        }
    }

    /** The CA's private key in PEM, for persisting it next to the CA certificate. */
    public static String privateKeyPem(CertificateAuthority ca) {
        try {
            return toPem(ca.privateKey());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode CA private key", e);
        }
    }

    /** Reads a CA back from its certificate PEM and private key PEM (PKCS#1 or PKCS#8). */
    public static CertificateAuthority readAuthority(String certificatePem, String privateKeyPem) {
        try {
            X509Certificate certificate;
            try (PEMParser parser = new PEMParser(new StringReader(certificatePem))) {
                Object parsed = parser.readObject();
                if (!(parsed instanceof X509CertificateHolder holder)) {
                    throw new IllegalStateException("CA certificate PEM holds no certificate");
                }
                certificate = new JcaX509CertificateConverter().getCertificate(holder);
            }
            PrivateKey privateKey;
            try (PEMParser parser = new PEMParser(new StringReader(privateKeyPem))) {
                Object parsed = parser.readObject();
                JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
                privateKey = switch (parsed) {
                    case PEMKeyPair pair -> converter.getKeyPair(pair).getPrivate();
                    case PrivateKeyInfo info -> converter.getPrivateKey(info);
                    case null, default -> throw new IllegalStateException("CA key PEM holds no private key");
                };
            }
            return new CertificateAuthority(certificate, privateKey);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read TLS certificate authority", e);
        }
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, SECURE_RANDOM);
        return keyGen.generateKeyPair();
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

            JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
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
