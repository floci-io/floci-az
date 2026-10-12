package io.floci.az.services.eventhub;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtemisTlsGeneratorTest {

    @Test
    void generatesPemCertificateAndPkcs12Keystore() throws Exception {
        ArtemisTlsGenerator.TlsBundle bundle =
                new ArtemisTlsGenerator().generate("floci-az-servicebus-default");

        assertTrue(bundle.certPem().contains("BEGIN CERTIFICATE"));
        assertTrue(bundle.pkcs12Bytes().length > 0);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(new ByteArrayInputStream(bundle.pkcs12Bytes()),
                ArtemisTlsGenerator.KEYSTORE_PASSWORD.toCharArray());
        assertTrue(keyStore.containsAlias("artemis"));

        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate = (X509Certificate) certificateFactory.generateCertificate(
                new ByteArrayInputStream(bundle.certPem().getBytes()));
        Collection<List<?>> sans = certificate.getSubjectAlternativeNames();
        assertNotNull(sans);
        assertTrue(sans.stream().anyMatch(san -> san.contains("localhost")));
        assertTrue(sans.stream().anyMatch(san -> san.contains("floci-az-servicebus-default")));
    }

    /** In published endpoint mode the broker is reached at the Docker daemon's address, often an IP. */
    @Test
    void ipLiteralHostsBecomeIpAddressSans() throws Exception {
        ArtemisTlsGenerator.TlsBundle bundle =
                new ArtemisTlsGenerator().generate("floci-az-artemis-ns", "10.0.0.7", "[::1]", "build-host");

        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(bundle.certPem().getBytes()));
        Collection<List<?>> sans = certificate.getSubjectAlternativeNames();

        assertTrue(sans.contains(List.of(IP_ADDRESS_SAN, "10.0.0.7")), sans.toString());
        assertTrue(sans.stream().anyMatch(san -> san.get(0).equals(IP_ADDRESS_SAN)
                && san.get(1).toString().startsWith("0:0:0:0:0:0:0:1")), sans.toString());
        assertTrue(sans.contains(List.of(DNS_NAME_SAN, "build-host")), sans.toString());
        assertTrue(sans.contains(List.of(DNS_NAME_SAN, "floci-az-artemis-ns")), sans.toString());
    }

    @Test
    void aHostAlreadyCoveredIsNotRepeated() throws Exception {
        ArtemisTlsGenerator.TlsBundle bundle = new ArtemisTlsGenerator().generate("localhost", "127.0.0.1");

        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(bundle.certPem().getBytes()));

        assertEquals(3, certificate.getSubjectAlternativeNames().size());
    }

    private static final Integer DNS_NAME_SAN = 2;
    private static final Integer IP_ADDRESS_SAN = 7;
}
