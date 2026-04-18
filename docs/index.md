# Floci-AZ

<p align="center">
  <img src="assets/floci.png" alt="Floci-AZ" width="500" />
</p>

<p align="center"><em>Light, fluffy, and always free</em></p>

---

Floci-AZ is a fast, free, and open-source local Azure service emulator — providing Blob Storage, Queues, Tables, and Azure Functions in a single native binary.

## Why floci-az?

| | floci-az | [Azurite](https://github.com/Azure/Azurite) | [Functions Core Tools](https://github.com/Azure/azure-functions-core-tools) |
|---|---|---|---|
| Blob Storage | ✅ | ✅ | ❌ |
| Queue Storage | ✅ | ✅ | ❌ |
| Table Storage | ✅ | ✅ | ❌ |
| Azure Functions | ✅ | ❌ | ✅ |
| Startup time | **fast** | Moderate | Fast |
| Native binary | ✅ | ❌ | ✅ |
| Unified port (4577) | ✅ | ❌ | ❌ |
| Per-service storage modes | ✅ | ❌ | ❌ |
| WAL / hybrid persistence | ✅ | ❌ | ❌ |
| License | **MIT** | MIT | MIT |

## Architecture Overview

```mermaid
flowchart LR
    Client["☁️ Azure SDK / CLI"]

    subgraph floci-az ["floci-az — port 4577"]
        Router["HTTP Router\n(JAX-RS / Vert.x)"]

        subgraph Services ["Services"]
            A["Blob Storage\n/{account}/"]
            B["Queue Storage\n/{account}-queue/"]
            C["Table Storage\n/{account}-table/"]
            D["Azure Functions\n/{account}-functions/"]
        end

        Router --> A
        Router --> B
        Router --> C
        Router --> D
        A & B & C --> Store[("StorageBackend\nmemory · hybrid\npersistent · wal")]
        D -->|"spawn / proxy"| Docker["🐳 Docker\n(function containers)"]
    end

    Client -->|"HTTP :4577\nAzure wire protocol"| Router
```

## Supported Services

| Service | Routing | Notable operations |
|---|---|---|
| **Blob Storage** | `/{account}/` | Create/delete containers, upload/download/delete blobs, list blobs |
| **Queue Storage** | `/{account}-queue/` | Create/delete queues, send/receive/peek/delete messages, visibility timeout |
| **Table Storage** | `/{account}-table/` | Create/delete tables, insert/get/update/upsert/delete entities, list entities |
| **Azure Functions** | `/{account}-functions/` | Deploy & invoke HTTP-triggered functions (node, python, java, dotnet); warm-container pool |

## Quick Start

```yaml title="docker-compose.yml"
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - ./data:/app/data
      - /var/run/docker.sock:/var/run/docker.sock  # required for Azure Functions
```

```bash
docker compose up -d
```

All services are immediately available at `http://localhost:4577`.

[Get started →](getting-started/quick-start.md){ .md-button .md-button--primary }
[View services →](services/index.md){ .md-button }
