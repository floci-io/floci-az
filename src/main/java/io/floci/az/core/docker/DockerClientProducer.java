package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import io.floci.az.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * CDI producer for the DockerClient singleton bean.
 */
@ApplicationScoped
public class DockerClientProducer {

    private static final Logger LOG = Logger.getLogger(DockerClientProducer.class);

    private final EmulatorConfig config;

    @Inject
    public DockerClientProducer(EmulatorConfig config) {
        this.config = config;
    }

    /**
     * Normalizes a Docker host value by prepending {@code tcp://} when no recognized
     * URI scheme ({@code tcp://}, {@code unix://}, {@code npipe://}) is present.
     */
    static String normalizeDockerHost(String dockerHost) {
        if (dockerHost == null) {
            return null;
        }
        if (dockerHost.isEmpty()) {
            return dockerHost;
        }
        String lower = dockerHost.toLowerCase();
        if (lower.startsWith("tcp://") || lower.startsWith("unix://") || lower.startsWith("npipe://")) {
            return dockerHost;
        }
        String normalized = "tcp://" + dockerHost;
        LOG.infov("Docker host value ''{0}'' has no URI scheme; normalizing to ''{1}''", dockerHost, normalized);
        return normalized;
    }

    /**
     * Resolves the effective Docker host. Prefers {@code DOCKER_HOST} env var over the
     * configured default (unix socket), and normalizes bare host:port values to tcp://.
     */
    static String resolveEffectiveDockerHost(String configuredHost, String dockerHostEnv) {
        String normalizedEnvHost = normalizeDockerHost(dockerHostEnv);
        if ("unix:///var/run/docker.sock".equals(configuredHost)
                && normalizedEnvHost != null && !normalizedEnvHost.isBlank()) {
            return normalizedEnvHost;
        }
        return normalizeDockerHost(configuredHost);
    }

    private static DefaultDockerClientConfig.Builder createDockerConfigBuilder() {
        try {
            return DefaultDockerClientConfig.createDefaultConfigBuilder();
        } catch (IllegalArgumentException e) {
            // DOCKER_HOST env var is set without a URI scheme (e.g. "10.37.124.101:2375").
            // docker-java calls URI.create() on it immediately, which throws before our
            // withDockerHost() override can take effect. Fall back to a fresh builder.
            LOG.warnv("Could not initialize Docker config from environment "
                    + "(DOCKER_HOST env var may be missing a URI scheme): {0}. "
                    + "Using configured host.", e.getMessage());
            return new DefaultDockerClientConfig.Builder();
        }
    }

    @Produces
    @ApplicationScoped
    public DockerClient dockerClient() {
        String dockerHost = resolveEffectiveDockerHost(
                config.docker().dockerHost(), System.getenv("DOCKER_HOST"));
        LOG.infov("Creating DockerClient for host: {0}", dockerHost);

        DefaultDockerClientConfig.Builder builder = createDockerConfigBuilder();
        builder.withDockerHost(dockerHost);
        config.docker().dockerConfigPath().ifPresent(path -> {
            LOG.infov("Using Docker config path: {0}", path);
            builder.withDockerConfig(path);
        });
        DefaultDockerClientConfig clientConfig = builder.build();

        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofMinutes(5))
                .build();

        return DockerClientImpl.getInstance(clientConfig, httpClient);
    }
}
