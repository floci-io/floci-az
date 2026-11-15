package io.floci.az.core.tls;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The name constraints a CA carries, and the check that mirrors how Go, OpenSSL and Apple apply them. */
class CertificateGeneratorNameConstraintsTest {

    private static X509Certificate constrained;

    @BeforeAll
    static void generate() {
        constrained = new CertificateGenerator().generateAuthority(new CertificateGenerator.PermittedNames(
                List.of("localhost", "vault.azure.net"), List.of("127.0.0.0/8", "::1/128"))).certificate();
    }

    @Test
    void aDnsSubtreeCoversTheNameItsSubdomainsAndWildcardsUnderIt() {
        assertTrue(CertificateGenerator.permits(constrained, "vault.azure.net"));
        assertTrue(CertificateGenerator.permits(constrained, "kv1.vault.azure.net"));
        assertTrue(CertificateGenerator.permits(constrained, "*.vault.azure.net"));
        assertTrue(CertificateGenerator.permits(constrained, "API.LOCALHOST"));
        assertFalse(CertificateGenerator.permits(constrained, "notvault.azure.net"));
        assertFalse(CertificateGenerator.permits(constrained, "vault.azure.net.example.com"));
    }

    @Test
    void anIpRangeCoversItsAddressesOnlyInItsOwnFamily() {
        assertTrue(CertificateGenerator.permits(constrained, "127.0.0.1"));
        assertTrue(CertificateGenerator.permits(constrained, "127.255.255.254"));
        assertTrue(CertificateGenerator.permits(constrained, "[::1]"));
        assertFalse(CertificateGenerator.permits(constrained, "128.0.0.1"));
        assertFalse(CertificateGenerator.permits(constrained, "::2"));
    }

    @Test
    void anUnconstrainedCaPermitsEverything() {
        X509Certificate open = new CertificateGenerator().generateAuthority().certificate();

        assertFalse(CertificateGenerator.hasNameConstraints(open));
        assertTrue(CertificateGenerator.permits(open, "github.com"));
        assertTrue(CertificateGenerator.permits(open, "8.8.8.8"));
    }

    @Test
    void aRangeIsEncodedAsAddressThenMask() throws Exception {
        assertArrayEquals(new byte[] {10, 0, 0, 0, (byte) 255, 0, 0, 0}, CertificateGenerator.addressAndMask("10.0.0.0/8"));
        assertArrayEquals(new byte[] {(byte) 172, 16, 0, 0, (byte) 255, (byte) 240, 0, 0},
                CertificateGenerator.addressAndMask("172.16.0.0/12"));
        assertThrows(IllegalArgumentException.class, () -> CertificateGenerator.addressAndMask("10.0.0.0"));
        assertThrows(IllegalArgumentException.class, () -> CertificateGenerator.addressAndMask("10.0.0.0/33"));
    }

    @Test
    void ipInRangeAcceptsBracketedLiteralsAndRejectsGarbage() {
        assertTrue(CertificateGenerator.ipInRange("192.168.4.20", "192.168.0.0/16"));
        assertTrue(CertificateGenerator.ipInRange("[::1]", "::1/128"));
        assertFalse(CertificateGenerator.ipInRange("192.169.0.1", "192.168.0.0/16"));
        assertFalse(CertificateGenerator.ipInRange("127.0.0.1", "not-a-range"));
    }
}
