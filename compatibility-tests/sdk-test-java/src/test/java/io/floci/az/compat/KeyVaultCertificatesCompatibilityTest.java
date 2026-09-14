package io.floci.az.compat;

import com.azure.core.credential.AccessToken;
import com.azure.security.keyvault.certificates.CertificateClientBuilder;
import com.azure.security.keyvault.certificates.models.CertificatePolicy;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KeyVaultCertificatesCompatibilityTest {
    @Test
    void selfSignedCertificateExportsMatchingPrivateKey() throws Exception {
        EmulatorConfig.assumeEmulatorRunning();
        var client = new CertificateClientBuilder()
                .vaultUrl(EmulatorConfig.httpBase().replace("http://", "https://") + "/" + EmulatorConfig.ACCOUNT + "-keyvault")
                .credential(request -> Mono.just(new AccessToken("fake-token", OffsetDateTime.now().plusHours(1))))
                .addPolicy(new EmulatorConfig.ForceHttpPolicy())
                .disableChallengeResourceVerification().buildClient();
        String name = "cert-" + UUID.randomUUID();
        client.beginCreateCertificate(name, new CertificatePolicy("Self", "CN=SDK compatibility")).waitForCompletion();
        var certificate = client.getCertificate(name);
        var secret = EmulatorConfig.buildKeyVaultClient().getSecret(name, certificate.getProperties().getVersion());
        var pfx = KeyStore.getInstance("PKCS12");
        pfx.load(new ByteArrayInputStream(Base64.getDecoder().decode(secret.getValue())), new char[0]);
        String alias = pfx.aliases().nextElement();
        assertTrue(pfx.isKeyEntry(alias));
        assertArrayEquals(certificate.getCer(), pfx.getCertificate(alias).getEncoded());
        assertEquals(1, client.listPropertiesOfCertificateVersions(name).stream().count());
        client.beginDeleteCertificate(name).waitForCompletion();
        client.beginRecoverDeletedCertificate(name).waitForCompletion();
        assertEquals(certificate.getProperties().getVersion(), client.getCertificate(name).getProperties().getVersion());
        client.beginDeleteCertificate(name).waitForCompletion();
        client.purgeDeletedCertificate(name);
    }
}
