package io.floci.az.services.eventhub;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

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
}
