package io.floci.az.services.eventhub;

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

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Generates a self-signed TLS certificate and PKCS12 keystore for the Artemis AMQP broker.
 * The keystore is copied into the Artemis container; the cert PEM is served to SDK clients
 * so they can verify the TLS connection.
 */
@ApplicationScoped
public class ArtemisTlsGenerator {

    public static final String KEYSTORE_PASSWORD = "artemis-tls";
    private static final String CONTAINER_ALIAS = "artemis";

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public record TlsBundle(byte[] pkcs12Bytes, String certPem) {}

    /**
     * Generates a new self-signed cert and PKCS12 keystore valid for 10 years.
     * SANs cover the Artemis container hostname, localhost variants, and any additional
     * DNS names passed in (e.g. the Docker container name for the sidecar).
     */
    public TlsBundle generate(String... additionalDnsNames) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        keyGen.initialize(2048, new SecureRandom());
        KeyPair keyPair = keyGen.generateKeyPair();

        Instant now = Instant.now();
        X500Name name = new X500Name("CN=floci-az-artemis");
        BigInteger serial = new BigInteger(128, new SecureRandom());

        var certBuilder = new JcaX509v3CertificateBuilder(
                name, serial,
                Date.from(now), Date.from(now.plus(3650, ChronoUnit.DAYS)),
                name, keyPair.getPublic());

        java.util.List<GeneralName> sanList = new java.util.ArrayList<>();
        sanList.add(new GeneralName(GeneralName.dNSName, "floci-az-artemis"));
        sanList.add(new GeneralName(GeneralName.dNSName, "localhost"));
        sanList.add(new GeneralName(GeneralName.iPAddress,
                new DEROctetString(InetAddress.getByName("127.0.0.1").getAddress())));
        for (String dnsName : additionalDnsNames) {
            sanList.add(new GeneralName(GeneralName.dNSName, dnsName));
        }
        GeneralName[] sans = sanList.toArray(new GeneralName[0]);
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

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(CONTAINER_ALIAS, keyPair.getPrivate(), KEYSTORE_PASSWORD.toCharArray(),
                new java.security.cert.Certificate[]{cert});
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ks.store(baos, KEYSTORE_PASSWORD.toCharArray());

        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pw = new JcaPEMWriter(sw)) {
            pw.writeObject(cert);
        }

        return new TlsBundle(baos.toByteArray(), sw.toString());
    }
}
