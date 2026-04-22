package io.floci.az.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "floci-az")
public interface EmulatorConfig {

    @WithDefault("4577")
    int port();

    @WithDefault("http://localhost:4577")
    String baseUrl();

    /**
     * When set, overrides the hostname in base-url for URLs returned in API responses.
     */
    Optional<String> hostname();

    /**
     * Returns the effective base URL, taking hostname into account.
     */
    default String effectiveBaseUrl() {
        return hostname()
                .map(h -> baseUrl().replaceFirst("://[^:/]+(:\\d+)?", "://" + h + "$1"))
                .orElse(baseUrl());
    }

    StorageConfig storage();

    ServicesConfig services();

    AuthConfig auth();

    DockerConfig docker();

    interface StorageConfig {
        /** Supported modes: memory, persistent, hybrid, wal */
        @WithDefault("memory")
        String mode();

        @WithDefault("${user.home}/.floci-az/data")
        String path();

        /** The path on the host machine where data is stored. Useful for Docker-in-Docker. */
        @WithDefault("${floci-az.storage.path}")
        String hostPersistentPath();

        WalConfig wal();

        HybridConfig hybrid();

        ServicesStorageConfig services();
    }

    interface ServicesStorageConfig {
        ServiceStorageConfig blob();
        ServiceStorageConfig queue();
        ServiceStorageConfig table();
    }

    interface ServiceStorageConfig {
        /** When present, overrides the global storage mode for this service. */
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface WalConfig {
        @WithDefault("30000")
        long compactionIntervalMs();
    }

    interface HybridConfig {
        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface ServicesConfig {
        BlobServiceConfig  blob();
        QueueServiceConfig queue();
        TableServiceConfig table();
        FunctionsConfig    functions();
    }

    interface BlobServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface QueueServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface TableServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface AuthConfig {
        /** dev: accept any credentials. strict: validate HMAC-SHA256 signatures. */
        @WithDefault("dev")
        String mode();
    }

    interface FunctionsConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("${user.home}/.floci-az/functions")
        String codePath();

        /** When true, each invocation gets a fresh container (no warm reuse). */
        @WithDefault("false")
        boolean ephemeral();

        /** Evict warm containers idle longer than this (ms). */
        @WithDefault("300000")
        long idleTimeoutMs();
    }

    /**
     * Configuration for Docker container management shared across all services.
     */
    interface DockerConfig {

        /** Maximum size of each container log file before rotation. */
        @WithDefault("10m")
        String logMaxSize();

        /** Maximum number of rotated log files to retain per container. */
        @WithDefault("3")
        String logMaxFile();

        /** Unix socket or TCP URL for the Docker daemon. */
        @WithDefault("unix:///var/run/docker.sock")
        String dockerHost();

        /** Path to a directory containing Docker's config.json. */
        Optional<String> dockerConfigPath();

        /** Explicit credentials for private Docker registries. */
        @WithDefault("")
        List<RegistryCredential> registryCredentials();

        interface RegistryCredential {
            /** Registry hostname (e.g. myregistry.example.com). */
            String server();
            String username();
            String password();
        }
    }
}
