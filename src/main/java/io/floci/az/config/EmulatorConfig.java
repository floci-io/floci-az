package io.floci.az.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "floci-az")
public interface EmulatorConfig {

    @WithDefault("4577")
    int port();

    @WithDefault("4578")
    int sslPort();

    @WithDefault("http://localhost:4577")
    String baseUrl();

    @WithDefault("https://localhost:4578")
    String baseUrlHttps();

    /**
     * When set, overrides the hostname in base-url for URLs returned in API responses.
     */
    Optional<String> hostname();

    DnsConfig dns();

    /**
     * Returns the effective base URL, taking hostname into account.
     */
    default String effectiveBaseUrl() {
        return hostname()
                .map(h -> baseUrl().replaceFirst("://[^:/]+(:\\d+)?", "://" + h + "$1"))
                .orElse(baseUrl());
    }

    TlsConfig tls();

    StorageConfig storage();

    ServicesConfig services();

    AuthConfig auth();

    DockerConfig docker();

    interface StorageConfig {
        /** Supported modes: memory, persistent, hybrid, wal */
        @WithDefault("memory")
        String mode();

        @WithDefault("./data")
        String persistentPath();

        /** The path on the host machine where data is stored. Useful for Docker-in-Docker. */
        @WithDefault("${floci-az.storage.persistent-path}")
        String hostPersistentPath();

        /**
         * When {@code true}, named volumes are removed immediately after a child container stops
         * on resource delete. In {@code memory} storage mode volumes are always removed regardless
         * of this flag. Defaults to {@code false} to match real Azure behaviour (data survives delete).
         */
        @WithDefault("false")
        boolean pruneVolumesOnDelete();

        WalConfig wal();

        HybridConfig hybrid();

        ServicesStorageConfig services();
    }

    interface ServicesStorageConfig {
        ServiceStorageConfig blob();
        ServiceStorageConfig queue();
        ServiceStorageConfig table();
        ServiceStorageConfig appConfig();
        ServiceStorageConfig cosmos();
        ServiceStorageConfig keyVault();
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
        BlobServiceConfig      blob();
        QueueServiceConfig     queue();
        TableServiceConfig     table();
        FunctionsConfig        functions();
        AppConfigServiceConfig appConfig();
        CosmosServiceConfig    cosmos();
        KeyVaultConfig         keyVault();
        EventHubConfig         eventHub();
        SqlServiceConfig       sql();

        /** Shared Docker network for sidecar containers (Artemis, Redpanda, etc.). */
        Optional<String> dockerNetwork();
    }

    interface EventHubConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("emulatorNs1")
        String defaultNamespace();

        /** Comma-separated "name:partitions" pairs, e.g. "eh1:4,eh2:2". */
        @WithDefault("eh1:4")
        String entities();

        @WithDefault("5672")
        int amqpPort();

        /** TLS AMQP port for uamqp / Python SDK clients that require TLS. */
        @WithDefault("5671")
        int amqpTlsPort();

        @WithDefault("false")
        boolean kafkaEnabled();

        @WithDefault("9093")
        int kafkaPort();

        @WithDefault("apache/activemq-artemis:latest")
        String artemisImage();

        @WithDefault("redpandadata/redpanda:latest")
        String redpandaImage();

        /** Comma-separated consumer group names created on every event hub, e.g. "$Default,my-group". */
        @WithDefault("$Default,my-consumer-group")
        String consumerGroups();
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

    interface DnsConfig {
        /**
         * Additional hostname suffixes resolved to floci-az's container IP by the
         * embedded DNS server. Used when function containers need to reach floci-az
         * via a custom domain name (e.g. "myhost.internal").
         */
        Optional<List<String>> extraSuffixes();
    }

    interface AuthConfig {
        /** dev: accept any credentials. strict: validate HMAC-SHA256 signatures. */
        @WithDefault("dev")
        String mode();
    }

    interface AppConfigServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface CosmosServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithName("engines")
        CosmosEngineConfig engines();
    }

    interface CosmosEngineConfig {
        /** Startup mode: "on-demand" (default), "eager", or "disabled". */
        @WithDefault("on-demand")
        String startup();

        @WithDefault("nosql")
        String defaultApi();

        CosmosApiConfig nosql();
        CosmosApiConfig mongodb();
        CosmosApiConfig postgresql();
        CosmosApiConfig cassandra();
        CosmosApiConfig gremlin();
        CosmosApiConfig table();
    }

    interface CosmosApiConfig {
        /** Whether this API engine is enabled. */
        @WithDefault("false")
        boolean enabled();

        /** Docker image override (optional). */
        Optional<String> image();

        /** Host port override (optional). */
        Optional<Integer> port();
    }

    interface KeyVaultConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface SqlServiceConfig {
        /** Enable or disable the Azure SQL Database service. */
        @WithDefault("true")
        boolean enabled();

        /**
         * Must be set to "Y" to accept the Microsoft SQL Server EULA.
         * The service will return 503 until this is explicitly set.
         */
        @WithDefault("")
        String acceptEula();

        /** Docker image for the SQL Server container. */
        @WithDefault("mcr.microsoft.com/azure-sql-edge:latest")
        String image();

        /**
         * SA (system administrator) password used when launching containers.
         * Must meet SQL Server complexity requirements (≥8 chars, upper+lower+digit+special).
         */
        @WithDefault("FlociAz_Strong123!")
        String saPassword();

        /**
         * Maximum seconds to wait for a SQL Server container to become ready.
         * SQL Server typically takes 10-20 s to initialise.
         */
        @WithDefault("60")
        int startupTimeoutSeconds();

        /** Default host port. 0 lets the OS pick a free port (recommended when running multiple servers). */
        @WithDefault("0")
        int defaultPort();
    }

    interface FunctionsConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("${user.home}/.floci-az/functions")
        String codePath();

        /** When true, each invocation gets a fresh container (no warm reuse). */
        @WithDefault("false")
        boolean ephemeral();

        /** Evict warm containers idle longer than this (seconds). 0 disables eviction. */
        @WithDefault("300")
        int containerIdleTimeoutSeconds();

        /** Overrides the hostname that function containers use to reach floci-az. */
        Optional<String> dockerHostOverride();
    }

    interface TlsConfig {
        /** Enable TLS/HTTPS. When true, both HTTP and HTTPS are served on the same public port. */
        @WithDefault("false")
        boolean enabled();

        /** Path to PEM certificate file. */
        Optional<String> certPath();

        /** Path to PEM private key file. */
        Optional<String> keyPath();

        /** Auto-generate a self-signed certificate when no cert-path/key-path provided. */
        @WithDefault("true")
        boolean selfSigned();
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
