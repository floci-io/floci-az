# application.yml Reference

Floci-AZ is a Quarkus application. All settings can be overridden via environment variables
(replace `.` with `_` and uppercase, e.g. `floci-az.storage.mode` → `FLOCI_AZ_STORAGE_MODE`).

```yaml
floci-az:
  port: 4577
  base-url: http://localhost:4577

  # When set, overrides the hostname used in URLs returned by the API
  # (e.g. blob SAS URLs, function invoke URLs).
  # hostname: myhost.internal

  auth:
    # dev:    accept any credentials without signature validation (default)
    # strict: validate HMAC-SHA256 shared-key signatures
    mode: dev

  storage:
    # Global default — applies to every service unless overridden below.
    # Supported: memory | persistent | hybrid | wal
    mode: memory
    path: /app/data

    wal:
      compaction-interval-ms: 30000

    hybrid:
      flush-interval-ms: 5000

    # Per-service storage overrides (uncomment to activate)
    services:
      blob:
        # mode: wal
        flush-interval-ms: 5000
      queue:
        # mode: hybrid
        flush-interval-ms: 5000
      table:
        # mode: persistent
        flush-interval-ms: 5000
      app-config:
        # mode: persistent
        flush-interval-ms: 5000

  dns:
    # When floci-az runs inside Docker, an embedded DNS server starts on UDP/53
    # and is injected into every Azure Functions container as their resolver.
    # It resolves *.hostname (above) and any extra suffixes to floci-az's
    # Docker-network IP so virtual-hosted URLs work from inside function containers.
    # extra-suffixes:
    #   - myapp.internal

  docker:
    docker-host: unix:///var/run/docker.sock
    log-max-size: "10m"
    log-max-file: "3"

  services:
    blob:
      enabled: true
    queue:
      enabled: true
    table:
      enabled: true
    functions:
      enabled: true
      # Directory where extracted function code is stored on the host.
      code-path: ~/.floci-az/functions
      # ephemeral: true  →  fresh container per invocation (no warm reuse)
      ephemeral: false
      # Evict warm containers idle longer than this (seconds); 0 disables eviction
      container-idle-timeout-seconds: 300
    app-config:
      enabled: true
```

## Key Environment Variables

| Environment Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_PORT` | `4577` | Listening port |
| `FLOCI_AZ_AUTH_MODE` | `dev` | `dev` or `strict` |
| `FLOCI_AZ_STORAGE_MODE` | `memory` | Global storage mode |
| `FLOCI_AZ_STORAGE_PATH` | `/app/data` | Persistence directory |
| `FLOCI_AZ_STORAGE_SERVICES_BLOB_MODE` | _(global)_ | Per-service blob mode |
| `FLOCI_AZ_STORAGE_SERVICES_QUEUE_MODE` | _(global)_ | Per-service queue mode |
| `FLOCI_AZ_STORAGE_SERVICES_TABLE_MODE` | _(global)_ | Per-service table mode |
| `FLOCI_AZ_STORAGE_SERVICES_APP_CONFIG_MODE` | _(global)_ | Per-service App Configuration mode |
| `FLOCI_AZ_SERVICES_FUNCTIONS_EPHEMERAL` | `false` | Fresh container per invocation |
| `FLOCI_AZ_SERVICES_FUNCTIONS_CONTAINER_IDLE_TIMEOUT_SECONDS` | `300` | Evict warm containers idle longer than this (seconds); `0` disables eviction |
| `FLOCI_AZ_SERVICES_APP_CONFIG_ENABLED` | `true` | Enable/disable App Configuration |
| `FLOCI_AZ_SERVICES_FUNCTIONS_CODE_PATH` | `~/.floci-az/functions` | Function code directory |
| `FLOCI_AZ_DOCKER_DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker daemon socket |
