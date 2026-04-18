package io.floci.az.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "floci-az")
public interface EmulatorConfig {

    @WithDefault("4577")
    int port();

    StorageConfig storage();

    ServicesConfig services();

    AuthConfig auth();

    interface StorageConfig {
        /** Supported modes: memory, persistent, hybrid, wal */
        @WithDefault("memory")
        String mode();

        @WithDefault("${user.home}/.floci-az/data")
        String path();

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

        @WithDefault("unix:///var/run/docker.sock")
        String dockerHost();

        @WithDefault("${user.home}/.floci-az/functions")
        String codePath();

        /** When true, each invocation gets a fresh container (no warm reuse). */
        @WithDefault("false")
        boolean ephemeral();

        /** Evict warm containers idle longer than this (ms). */
        @WithDefault("300000")
        long idleTimeoutMs();
    }
}
