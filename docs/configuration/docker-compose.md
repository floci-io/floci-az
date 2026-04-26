# Docker Compose Configuration

## Basic Setup

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - ./data:/app/data
      - /var/run/docker.sock:/var/run/docker.sock  # required for Azure Functions
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_PORT` | `4577` | Port the emulator listens on |
| `FLOCI_AZ_AUTH_MODE` | `dev` | `dev` (accept any credentials) or `strict` (validate HMAC-SHA256) |
| `FLOCI_AZ_STORAGE_MODE` | `memory` | Global storage mode: `memory`, `persistent`, `hybrid`, `wal` |
| `FLOCI_AZ_STORAGE_PATH` | `/app/data` | Directory for persisted state |
| `FLOCI_AZ_STORAGE_SERVICES_BLOB_MODE` | _(global)_ | Storage mode for Blob only |
| `FLOCI_AZ_STORAGE_SERVICES_QUEUE_MODE` | _(global)_ | Storage mode for Queue only |
| `FLOCI_AZ_STORAGE_SERVICES_TABLE_MODE` | _(global)_ | Storage mode for Table only |
| `FLOCI_AZ_SERVICES_FUNCTIONS_EPHEMERAL` | `false` | Fresh container per invocation |
| `FLOCI_AZ_SERVICES_FUNCTIONS_IDLE_TIMEOUT_MS` | `300000` | Warm-pool idle eviction (ms) |
| `FLOCI_AZ_DOCKER_DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker daemon socket path |

## With Persistent Storage

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
      FLOCI_AZ_STORAGE_MODE: hybrid
```

## Docker Socket Access

The Docker socket mount (`/var/run/docker.sock`) is required for Azure Functions. The container
entrypoint automatically detects the socket's group ID at runtime and grants the `floci` process
access — this handles both Docker Desktop (macOS/Windows) and native Linux Docker without any
manual group configuration.

## Named Network (Recommended for Integration Tests)

When running tests in other containers that need to reach floci-az, place everything on a
shared network:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    networks:
      - test-net

networks:
  test-net:
    name: test-net
```

Your test containers can then reach floci-az at `http://floci-az:4577`.
