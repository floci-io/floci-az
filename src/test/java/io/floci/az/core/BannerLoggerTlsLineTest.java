package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A client that does not trust floci-az's CA fails with a bare x509 error, so the banner says where the CA is
 * and how to trust it.
 */
class BannerLoggerTlsLineTest {

    @Test
    void withTlsOffTheLineSaysHowToTurnItOn() {
        EmulatorConfig config = config(false, Optional.empty(), Optional.empty());

        assertEquals("TLS: off (FLOCI_AZ_TLS_ENABLED=true serves HTTPS on the same port; Terraform needs it)",
                BannerLogger.tlsLine(config));
    }

    @Test
    void withASelfSignedCertificateTheLinePointsAtTheCaAndTheTrustSteps() {
        EmulatorConfig config = config(true, Optional.empty(), Optional.empty());

        assertEquals("TLS: on, self-signed. Clients must trust the CA at http://localhost:4577/_floci/tls-cert, "
                + "see https://floci.io/floci-az/terraform/#2-trust-the-certificate", BannerLogger.tlsLine(config));
    }

    @Test
    void withAUserCertificateTheLineNamesItAndGivesNoTrustSteps() {
        EmulatorConfig config = config(true, Optional.of("/certs/floci.crt"), Optional.of("/certs/floci.key"));

        assertEquals("TLS: on, certificate /certs/floci.crt", BannerLogger.tlsLine(config));
    }

    @Test
    void withACaOfTheUsersTheLineNamesIt() {
        EmulatorConfig config = config(true, Optional.empty(), Optional.empty());
        when(config.tls().caCertPath()).thenReturn(Optional.of("/certs/rootCA.pem"));

        assertEquals("TLS: on, certificate issued by your CA /certs/rootCA.pem", BannerLogger.tlsLine(config));
    }

    @Test
    void aBlankCertificatePathCountsAsSelfSigned() {
        EmulatorConfig config = config(true, Optional.of(""), Optional.of(""));

        assertEquals("TLS: on, self-signed. Clients must trust the CA at http://localhost:4577/_floci/tls-cert, "
                + "see https://floci.io/floci-az/terraform/#2-trust-the-certificate", BannerLogger.tlsLine(config));
    }

    private static EmulatorConfig config(boolean enabled, Optional<String> certPath, Optional<String> keyPath) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(enabled);
        when(config.tls().certPath()).thenReturn(certPath);
        when(config.tls().keyPath()).thenReturn(keyPath);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4577");
        return config;
    }
}
