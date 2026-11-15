package io.floci.az.core.tls;

import io.floci.az.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Server certificates for sidecars that terminate TLS themselves (a Redis cache's SSL port), so a
 * client trusts them the same way it trusts the emulator's own HTTPS endpoint.
 *
 * <ul>
 *   <li>Emulator TLS on, self-signed: a leaf for the sidecar's host names, issued by the emulator
 *       CA, which is what {@code GET /_floci/tls-cert} serves.</li>
 *   <li>Emulator TLS on with a CA of the user's ({@code FLOCI_AZ_TLS_CA_CERT_PATH}): a leaf issued
 *       by that CA, which clients already trust.</li>
 *   <li>Emulator TLS on with a user-provided certificate, or a self-signed CA written by an older
 *       floci-az, which did not keep its key: the emulator's own certificate and key, as the HTTPS
 *       endpoint serves them.</li>
 *   <li>Emulator TLS off: a leaf issued by a CA generated once per process and never written to
 *       disk. The caller ships the CA with the sidecar so clients can fetch it.</li>
 * </ul>
 */
@ApplicationScoped
public class SidecarCertificates {

    private static final Logger LOG = Logger.getLogger(SidecarCertificates.class);

    /** Names every leaf carries besides the sidecar's own: how clients on the Docker host reach it. */
    private static final List<String> COMMON_HOSTS = List.of("localhost", "127.0.0.1", "host.docker.internal");

    /**
     * @param chainPem      the server certificate followed by its CA, in PEM
     * @param privateKeyPem the server certificate's private key, in PEM
     * @param caPem         the certificate a client trusts to verify {@code chainPem}
     */
    public record ServerCertificate(String chainPem, String privateKeyPem, String caPem) {}

    private final EmulatorConfig config;
    private final CertificateGenerator generator;
    private CertificateGenerator.CertificateAuthority processAuthority;

    @Inject
    public SidecarCertificates(EmulatorConfig config, CertificateGenerator generator) {
        this.config = config;
        this.generator = generator;
    }

    /** A server certificate valid for {@code hosts} (and the loopback names) under the active trust anchor. */
    public synchronized ServerCertificate issue(Collection<String> hosts) {
        List<String> sans = sans(hosts);
        if (!config.tls().enabled()) {
            return fromGenerated(generator.issue(processAuthority(), sans));
        }
        Optional<CertificateGenerator.CertificateAuthority> emulatorAuthority = emulatorAuthority();
        if (emulatorAuthority.isPresent()) {
            warnOutsideConstraints(emulatorAuthority.get(), sans);
            return fromGenerated(generator.issue(emulatorAuthority.get(), sans));
        }
        return emulatorCertificate();
    }

    /**
     * A name outside the CA's constraints makes clients that enforce them (Go, OpenSSL, Apple) reject
     * the whole certificate. floci-az's own CA is replaced at the next start when the name is one it
     * knows about up front, so a sidecar name outside it is a bug worth seeing in the log. A CA the
     * user brings is never replaced; its constraints are theirs.
     */
    private static void warnOutsideConstraints(CertificateGenerator.CertificateAuthority authority, List<String> sans) {
        List<String> outside = sans.stream()
                .filter(san -> !CertificateGenerator.permits(authority.certificate(), san))
                .toList();
        if (!outside.isEmpty()) {
            LOG.warnv("TLS: the emulator CA may not issue for {0}; clients that enforce its name constraints "
                    + "will reject this sidecar certificate", outside);
        }
    }

    static List<String> sans(Collection<String> hosts) {
        Set<String> all = new LinkedHashSet<>(COMMON_HOSTS);
        for (String host : hosts) {
            if (host != null && !host.isBlank()) {
                all.add(host);
            }
        }
        return new ArrayList<>(all);
    }

    private CertificateGenerator.CertificateAuthority processAuthority() {
        if (processAuthority == null) {
            processAuthority = generator.generateAuthority();
            LOG.info("TLS: generated an in-memory CA for sidecar certificates (floci-az TLS is off)");
        }
        return processAuthority;
    }

    /** The CA the emulator's own certificate comes from, when floci-az holds its key: the user's, or its own. */
    private Optional<CertificateGenerator.CertificateAuthority> emulatorAuthority() {
        if (userProvidedCertificate()) {
            return Optional.empty();
        }
        if (userAuthorityConfigured()) {
            return Optional.of(TlsConfigSource.userAuthority(config.tls().caCertPath().orElse(""),
                    config.tls().caKeyPath().orElse("")));
        }
        Path dir = TlsConfigSource.selfSignedDir(config.storage().persistentPath());
        Path caFile = dir.resolve(TlsConfigSource.SELF_SIGNED_CA_NAME);
        Path caKeyFile = dir.resolve(TlsConfigSource.SELF_SIGNED_CA_KEY_NAME);
        if (!Files.isReadable(caFile) || !Files.isReadable(caKeyFile)) {
            return Optional.empty();
        }
        CertificateGenerator.CertificateAuthority authority =
                CertificateGenerator.readAuthority(read(caFile), read(caKeyFile));
        if (!authority.keyMatchesCertificate()) {
            // Leaves signed with it would not verify against the CA clients are told to trust.
            LOG.warnv("TLS: {0} is not the key of {1}; serving the emulator certificate to sidecars instead",
                    caKeyFile, caFile);
            return Optional.empty();
        }
        return Optional.of(authority);
    }

    /** The emulator's own HTTPS certificate, key and trust anchor, served unchanged. */
    private ServerCertificate emulatorCertificate() {
        if (userProvidedCertificate()) {
            String chain = read(Path.of(config.tls().certPath().orElseThrow()));
            return new ServerCertificate(chain, read(Path.of(config.tls().keyPath().orElseThrow())), chain);
        }
        Path dir = TlsConfigSource.selfSignedDir(config.storage().persistentPath());
        LOG.infov("TLS: {0} has no CA key (written by an older floci-az), so sidecars serve the emulator "
                + "certificate as is until the CA is next replaced", dir);
        return new ServerCertificate(
                read(dir.resolve(TlsConfigSource.SELF_SIGNED_CERT_NAME)),
                read(dir.resolve(TlsConfigSource.SELF_SIGNED_KEY_NAME)),
                read(dir.resolve(TlsConfigSource.SELF_SIGNED_CA_NAME)));
    }

    private boolean userProvidedCertificate() {
        return config.tls().certPath().filter(p -> !p.isBlank()).isPresent()
                && config.tls().keyPath().filter(p -> !p.isBlank()).isPresent();
    }

    private boolean userAuthorityConfigured() {
        return config.tls().caCertPath().filter(p -> !p.isBlank()).isPresent()
                || config.tls().caKeyPath().filter(p -> !p.isBlank()).isPresent();
    }

    private static ServerCertificate fromGenerated(CertificateGenerator.GeneratedCertificate generated) {
        return new ServerCertificate(generated.chainPem(), generated.privateKeyPem(), generated.caPem());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read TLS material " + file, e);
        }
    }
}
