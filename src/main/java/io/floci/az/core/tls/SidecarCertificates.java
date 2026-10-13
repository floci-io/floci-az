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
 *   <li>Emulator TLS on with a user-provided certificate, or a self-signed CA generated without its
 *       key (sidecar TLS was off then): the emulator's own certificate and key, as the HTTPS
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
            return fromGenerated(generator.issue(emulatorAuthority.get(), sans));
        }
        return emulatorCertificate();
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

    /** The self-signed emulator CA, when floci-az generated it and kept its key. */
    private Optional<CertificateGenerator.CertificateAuthority> emulatorAuthority() {
        if (userProvidedCertificate()) {
            return Optional.empty();
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
        LOG.infov("TLS: {0} has no CA key, so sidecars serve the emulator certificate as is; "
                + "delete that directory to regenerate it with one", dir);
        return new ServerCertificate(
                read(dir.resolve(TlsConfigSource.SELF_SIGNED_CERT_NAME)),
                read(dir.resolve(TlsConfigSource.SELF_SIGNED_KEY_NAME)),
                read(dir.resolve(TlsConfigSource.SELF_SIGNED_CA_NAME)));
    }

    private boolean userProvidedCertificate() {
        return config.tls().certPath().filter(p -> !p.isBlank()).isPresent()
                && config.tls().keyPath().filter(p -> !p.isBlank()).isPresent();
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
