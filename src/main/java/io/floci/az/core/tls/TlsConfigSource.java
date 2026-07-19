package io.floci.az.core.tls;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A MicroProfile {@link ConfigSource} that dynamically provides Quarkus TLS/SSL
 * configuration when {@code floci-az.tls.enabled=true}.
 *
 * <p>This runs <em>before</em> the Quarkus HTTP server starts, which is critical
 * because Quarkus reads {@code quarkus.http.ssl.*} properties during server
 * initialization. A CDI {@code @Startup} bean or {@code StartupEvent} observer
 * would be too late.
 *
 * <p>When TLS is enabled with self-signed mode, a certificate is generated using
 * {@link CertificateGenerator} and persisted under {@code {storage.persistent-path}/tls/}
 * for reuse across restarts. Hostname changes (via {@code floci-az.hostname} or
 * {@code floci-az.base-url}) trigger automatic certificate regeneration.
 *
 * <p>Both HTTP and HTTPS are served on the same public port via a
 * {@link TlsProxyServer} that detects the protocol from the first byte of each
 * incoming connection.
 */
public class TlsConfigSource implements ConfigSource {

    private static final Logger LOG = Logger.getLogger(TlsConfigSource.class);

    private static final String SELF_SIGNED_CERT_NAME     = "floci-az-selfsigned.crt";
    private static final String SELF_SIGNED_KEY_NAME      = "floci-az-selfsigned.key";
    private static final String SELF_SIGNED_METADATA_NAME = "floci-az-selfsigned.metadata.json";
    private static final String TLS_DIR = "tls";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Internal ports used when TLS proxy is active
    static final int HTTP_INTERNAL_PORT  = 4570;
    static final int HTTPS_INTERNAL_PORT = 4571;

    /** PEM content of the active TLS certificate, or {@code null} when TLS is disabled. */
    public static volatile String currentCertPem = null;

    private final Map<String, String> properties = new HashMap<>();

    public TlsConfigSource() {
        String enabled = resolveProperty("floci-az.tls.enabled", "false");
        if (!"true".equalsIgnoreCase(enabled)) {
            LOG.debug("TLS disabled — TlsConfigSource inactive");
            return;
        }

        String certPath       = resolveProperty("floci-az.tls.cert-path", "");
        String keyPath        = resolveProperty("floci-az.tls.key-path", "");
        String selfSigned     = resolveProperty("floci-az.tls.self-signed", "true");
        String persistentPath = resolveProperty("floci-az.storage.persistent-path", "./data");

        if (!certPath.isBlank() && !keyPath.isBlank()) {
            validateFileExists(certPath, "TLS certificate");
            validateFileExists(keyPath, "TLS private key");
            LOG.infov("TLS: using user-provided certificate: {0}", certPath);
        } else if ("true".equalsIgnoreCase(selfSigned)) {
            Path tlsDir   = Path.of(persistentPath, TLS_DIR);
            Path certFile = tlsDir.resolve(SELF_SIGNED_CERT_NAME);
            Path keyFile  = tlsDir.resolve(SELF_SIGNED_KEY_NAME);

            List<String> customHostnames = extractCustomHostnames();
            List<String> allSans = buildSanList(customHostnames);

            if (Files.exists(certFile) && Files.exists(keyFile)) {
                if (hostnameConfigChanged(tlsDir, allSans)) {
                    generateSelfSignedCert(tlsDir, certFile, keyFile, allSans);
                } else {
                    LOG.infov("TLS: reusing existing self-signed certificate: {0}", certFile);
                }
            } else {
                generateSelfSignedCert(tlsDir, certFile, keyFile, allSans);
            }

            certPath = certFile.toAbsolutePath().toString();
            keyPath  = keyFile.toAbsolutePath().toString();
        } else {
            throw new IllegalStateException(
                    "TLS enabled but no certificate provided and self-signed generation disabled. "
                            + "Set FLOCI_AZ_TLS_CERT_PATH + FLOCI_AZ_TLS_KEY_PATH, or enable FLOCI_AZ_TLS_SELF_SIGNED.");
        }

        properties.put("quarkus.http.ssl.certificate.files", toConfigPath(certPath));
        properties.put("quarkus.http.ssl.certificate.key-files", toConfigPath(keyPath));
        // When TLS is enabled, Quarkus HTTP and HTTPS run on internal ports.
        // TlsProxyServer listens on the public floci-az port and routes by protocol.
        properties.put("quarkus.http.insecure-requests", "enabled");
        properties.put("quarkus.http.host", "127.0.0.1");
        properties.put("quarkus.http.port", String.valueOf(HTTP_INTERNAL_PORT));
        properties.put("quarkus.http.ssl-port", String.valueOf(HTTPS_INTERNAL_PORT));

        try {
            currentCertPem = Files.readString(Path.of(certPath));
        } catch (IOException e) {
            LOG.warnv("TLS: could not read cert PEM for /_floci/tls-cert endpoint: {0}", e.getMessage());
        }

        LOG.infov("TLS: HTTPS enabled — proxy will listen on port {0} (HTTP+HTTPS), cert={1}",
                resolveProperty("floci-az.port", "4577"), certPath);
    }

    @Override
    public int getOrdinal() {
        // Higher than application.yml (250) so TLS properties take precedence
        return 300;
    }

    @Override
    public Set<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
    }

    @Override
    public String getName() {
        return "FlociAzTlsConfigSource";
    }

    /**
     * Converts a filesystem path to a form that survives config processing.
     *
     * <p>SmallRye Config treats backslashes in property values as escape
     * characters, so a Windows path such as {@code D:\data\tls\cert.crt}
     * reaches the Quarkus HTTP server as {@code D:datatlscert.crt} and TLS
     * startup fails with {@code NoSuchFileException}. The Windows file APIs
     * accept forward slashes, so the separators are swapped. On platforms
     * whose separator is {@code /} a backslash is a legal filename character,
     * so the path is left untouched.</p>
     */
    static String toConfigPath(String path) {
        return toConfigPath(path, File.separatorChar);
    }

    static String toConfigPath(String path, char separatorChar) {
        return separatorChar == '\\' ? path.replace('\\', '/') : path;
    }

    /**
     * Resolves a property from system properties or environment variables.
     * Environment variable names follow the MicroProfile convention:
     * {@code floci-az.tls.enabled} → {@code FLOCI_AZ_TLS_ENABLED}.
     */
    static String resolveProperty(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        String envKey = key.replace('.', '_').replace('-', '_').toUpperCase();
        value = System.getenv(envKey);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return defaultValue;
    }

    private static List<String> buildSanList(List<String> customHostnames) {
        List<String> all = new ArrayList<>();
        all.addAll(List.of("localhost", "127.0.0.1", "0.0.0.0", "*.localhost",
                "localhost.floci-az.io", "*.localhost.floci-az.io",
                "*.vault.azure.net"));
        all.addAll(customHostnames);
        return all;
    }

    private List<String> extractCustomHostnames() {
        Set<String> hostnames = new LinkedHashSet<>();

        String hostname = resolveProperty("floci-az.hostname", "");
        if (!hostname.isBlank() && !isDefaultHostname(hostname)) {
            hostnames.add(hostname);
            LOG.debugv("TLS: extracted hostname from floci-az.hostname: {0}", hostname);
        }

        String baseUrl = resolveProperty("floci-az.base-url", "http://localhost:4577");
        try {
            URI uri = new URI(baseUrl);
            String host = uri.getHost();
            if (host != null && !isDefaultHostname(host)) {
                hostnames.add(host);
                LOG.debugv("TLS: extracted hostname from floci-az.base-url: {0}", host);
            }
        } catch (URISyntaxException e) {
            LOG.warnv("TLS: failed to parse base URL for hostname extraction: {0}", baseUrl);
        }

        List<String> result = new ArrayList<>(hostnames);
        if (!result.isEmpty()) {
            LOG.infov("TLS: detected custom hostnames: {0}", result);
        }
        return result;
    }

    private boolean isDefaultHostname(String hostname) {
        return hostname.equals("localhost")
                || hostname.equals("127.0.0.1")
                || hostname.equals("0.0.0.0");
    }

    private void generateSelfSignedCert(Path tlsDir, Path certFile, Path keyFile, List<String> sans) {
        try {
            Files.createDirectories(tlsDir);

            // BouncyCastle may not be registered yet — ConfigSource runs before CDI
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }

            CertificateGenerator.GeneratedCertificate generated =
                    new CertificateGenerator().generateCertificate(sans);

            Files.writeString(certFile, generated.certificatePem());
            Files.writeString(keyFile, generated.privateKeyPem());

            LOG.infov("TLS: generated self-signed certificate: {0}", certFile);
            persistMetadata(tlsDir, sans);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write self-signed TLS certificate", e);
        }
    }

    private void persistMetadata(Path tlsDir, List<String> hostnames) {
        Path metadataFile = tlsDir.resolve(SELF_SIGNED_METADATA_NAME);
        try {
            String version = System.getenv("FLOCI_AZ_VERSION");
            if (version == null || version.isBlank()) {
                version = "dev";
            }
            Map<String, Object> metadata = Map.of("hostnames", hostnames, "version", version);
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
            Files.writeString(metadataFile, json);
            LOG.debugv("TLS: persisted certificate metadata: {0}", metadataFile);
        } catch (IOException e) {
            LOG.warnv("TLS: failed to write certificate metadata (will regenerate on next restart): {0}", e.getMessage());
        }
    }

    private boolean hostnameConfigChanged(Path tlsDir, List<String> currentSans) {
        Path metadataFile = tlsDir.resolve(SELF_SIGNED_METADATA_NAME);
        if (!Files.exists(metadataFile)) {
            LOG.infov("TLS: metadata file missing, regenerating certificate");
            return true;
        }
        try {
            String json = Files.readString(metadataFile);
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = OBJECT_MAPPER.readValue(json, Map.class);
            @SuppressWarnings("unchecked")
            List<String> previousSans = (List<String>) metadata.get("hostnames");
            if (previousSans == null) {
                LOG.warnv("TLS: metadata file has no hostnames field, regenerating certificate");
                return true;
            }
            if (!new LinkedHashSet<>(previousSans).equals(new LinkedHashSet<>(currentSans))) {
                LOG.infov("TLS: hostname configuration changed, regenerating certificate");
                return true;
            }
            LOG.debugv("TLS: hostname configuration unchanged, reusing certificate");
            return false;
        } catch (IOException e) {
            LOG.warnv("TLS: failed to read metadata file (will regenerate certificate): {0}", e.getMessage());
            return true;
        }
    }

    private static void validateFileExists(String path, String description) {
        if (!Files.isReadable(Path.of(path))) {
            throw new IllegalStateException(
                    description + " file not found or not readable: " + path);
        }
    }
}
