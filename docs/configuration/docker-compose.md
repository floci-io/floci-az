# Docker Compose Configuration

Most users configure floci-az entirely through environment variables — no config files needed.
Every `floci-az.*` setting maps to a `FLOCI_AZ_*` env var (replace `.` with `_`, uppercase).

---

## Common Scenarios

### Storage only (no Functions)

The simplest setup — skips the Docker socket mount entirely:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    environment:
      FLOCI_AZ_SERVICES_FUNCTIONS_ENABLED: "false"
```

### All services (default)

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock  # required for Azure Functions
```

### With persistent storage

Data survives container restarts. Mount a local directory and set a storage mode:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - ./data:/app/data
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_AZ_STORAGE_MODE: hybrid        # in-memory + async flush every 5 s (recommended)
      # FLOCI_AZ_STORAGE_MODE: wal         # every write goes to disk before responding
      # FLOCI_AZ_STORAGE_MODE: persistent  # flush only on graceful shutdown
```

### CI / Ephemeral — maximum speed

Pure in-memory, no socket required, fastest startup:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    environment:
      FLOCI_AZ_STORAGE_MODE: memory
      FLOCI_AZ_SERVICES_FUNCTIONS_ENABLED: "false"
```

### Selective services

Disable services you don't use:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    environment:
      FLOCI_AZ_SERVICES_BLOB_ENABLED: "true"
      FLOCI_AZ_SERVICES_QUEUE_ENABLED: "true"
      FLOCI_AZ_SERVICES_TABLE_ENABLED: "false"
      FLOCI_AZ_SERVICES_FUNCTIONS_ENABLED: "false"
      FLOCI_AZ_SERVICES_APP_CONFIG_ENABLED: "false"
```

### Per-service storage override

Run most services in-memory, but use WAL for blob durability:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - ./data:/app/data
    environment:
      FLOCI_AZ_STORAGE_MODE: memory
      FLOCI_AZ_STORAGE_SERVICES_BLOB_MODE: wal
```

### Multi-container (your app + floci-az)

When your application also runs in Docker, use the service name as the hostname:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    networks:
      - app-net

  my-app:
    image: my-app:latest
    environment:
      AZURE_STORAGE_CONNECTION_STRING: >-
        DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;
        AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==;
        BlobEndpoint=http://floci-az:4577/devstoreaccount1;
        QueueEndpoint=http://floci-az:4577/devstoreaccount1-queue;
        TableEndpoint=http://floci-az:4577/devstoreaccount1-table;
      # App Configuration — https:// required by the SDK; use ForceHttp transport in your client
      AZURE_APPCONFIG_ENDPOINT: https://floci-az:4577/devstoreaccount1-appconfig
    depends_on:
      - floci-az
    networks:
      - app-net

networks:
  app-net:
```

---

## Environment Variable Reference

All variables are optional; the default applies when unset.

### Core

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_PORT` | `4577` | Port the emulator listens on |
| `FLOCI_AZ_BASE_URL` | `http://localhost:4577` | Base URL embedded in API responses |
| `FLOCI_AZ_HOSTNAME` | _(unset)_ | Override the hostname in SAS and invoke URLs (useful behind a reverse proxy) |
| `FLOCI_AZ_AUTH_MODE` | `dev` | `dev` — accept any credentials; `strict` — validate HMAC-SHA256 signatures |

### Storage

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_STORAGE_MODE` | `memory` | Global storage backend: `memory` · `persistent` · `hybrid` · `wal` |
| `FLOCI_AZ_STORAGE_PERSISTENT_PATH` | `/app/data` | Container-side directory for persisted state |
| `FLOCI_AZ_STORAGE_HOST_PERSISTENT_PATH` | _(same as above)_ | Host-side path when running Docker-in-Docker |
| `FLOCI_AZ_STORAGE_WAL_COMPACTION_INTERVAL_MS` | `30000` | How often the WAL is compacted (ms) |
| `FLOCI_AZ_STORAGE_HYBRID_FLUSH_INTERVAL_MS` | `5000` | How often hybrid mode flushes to disk (ms) |

### Per-service storage overrides

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_STORAGE_SERVICES_BLOB_MODE` | _(global)_ | Storage mode for Blob Storage only |
| `FLOCI_AZ_STORAGE_SERVICES_QUEUE_MODE` | _(global)_ | Storage mode for Queue Storage only |
| `FLOCI_AZ_STORAGE_SERVICES_TABLE_MODE` | _(global)_ | Storage mode for Table Storage only |
| `FLOCI_AZ_STORAGE_SERVICES_APP_CONFIG_MODE` | _(global)_ | Storage mode for App Configuration only |

### Enable / disable services

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_BLOB_ENABLED` | `true` | Enable or disable Blob Storage |
| `FLOCI_AZ_SERVICES_QUEUE_ENABLED` | `true` | Enable or disable Queue Storage |
| `FLOCI_AZ_SERVICES_TABLE_ENABLED` | `true` | Enable or disable Table Storage |
| `FLOCI_AZ_SERVICES_FUNCTIONS_ENABLED` | `true` | Enable or disable Azure Functions |
| `FLOCI_AZ_SERVICES_APP_CONFIG_ENABLED` | `true` | Enable or disable App Configuration |

### Azure Functions

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_FUNCTIONS_EPHEMERAL` | `false` | `true` — fresh container per invocation; `false` — reuse warm containers |
| `FLOCI_AZ_SERVICES_FUNCTIONS_CONTAINER_IDLE_TIMEOUT_SECONDS` | `300` | Evict warm containers idle longer than this; `0` disables eviction |
| `FLOCI_AZ_SERVICES_FUNCTIONS_CODE_PATH` | `~/.floci-az/functions` | Where extracted function code is stored on the host |
| `FLOCI_AZ_SERVICES_FUNCTIONS_DOCKER_HOST_OVERRIDE` | _(unset)_ | Override the hostname function containers use to reach floci-az |

### Docker daemon

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_DOCKER_DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker daemon socket — unix socket or `tcp://host:port` |
| `FLOCI_AZ_DOCKER_LOG_MAX_SIZE` | `10m` | Max log file size per function container |
| `FLOCI_AZ_DOCKER_LOG_MAX_FILE` | `3` | Max rotated log files per function container |
| `FLOCI_AZ_DOCKER_DOCKER_CONFIG_PATH` | _(unset)_ | Path to Docker `config.json` for private registry auth |

---

## Docker Socket Access

The Docker socket mount (`/var/run/docker.sock`) is required for Azure Functions. The container
entrypoint automatically detects the socket's group ID at runtime and adjusts permissions — this
works on both Docker Desktop (macOS/Windows) and native Linux Docker with no manual configuration.

If you don't need Functions, omit the socket mount and set `FLOCI_AZ_SERVICES_FUNCTIONS_ENABLED=false`.

---

## Health Check

floci-az exposes a health endpoint you can use in `depends_on` conditions:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:4577/health"]
      interval: 5s
      timeout: 3s
      retries: 5

  my-app:
    depends_on:
      floci-az:
        condition: service_healthy
```
