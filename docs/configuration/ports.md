# Ports Reference

Floci-AZ uses a **single HTTP port** for all management APIs, plus one HTTPS port for the Cosmos Java SDK.
Sidecar services (Event Hubs, Cosmos engines) bind their own ports directly on the host — they are not
proxied through floci-az.

## floci-az ports

| Port | Protocol | Purpose |
|---|---|---|
| `4577` | HTTP | All REST services (Blob, Queue, Table, Functions, App Config, Cosmos, Key Vault) |
| `4578` | HTTPS | Cosmos DB — Java SDK only (enforces TLS in gateway mode) |

Both ports are exposed by floci-az itself. Only publish these two in your `docker-compose.yml`.

## Path-based routing (port 4577)

All services share port 4577 and are routed by URL path prefix:

| Service | Path prefix |
|---|---|
| Blob Storage | `/{account}/` |
| Queue Storage | `/{account}-queue/` |
| Table Storage | `/{account}-table/` |
| Azure Functions | `/{account}-functions/` |
| App Configuration | `/{account}-appconfig/` |
| Cosmos DB | `/{account}-cosmos/` |
| Key Vault | `/{account}-keyvault/` |
| Event Hubs | `/{account}-eventhub/` |
| **Azure SQL Database** | `/{account}-sql/` or `/subscriptions/.../providers/Microsoft.Sql/...` |
| **Azure Kubernetes Service** | `/subscriptions/.../providers/Microsoft.ContainerService/...` |

---

## Sidecar container ports (dynamic)

Services that spin up Docker containers bind an **OS-assigned random port** directly to the host.
These ports are resolved at runtime via the Docker daemon — you never configure them manually.

| Service | Sidecar container | Protocol | How to discover port |
|---|---|---|---|
| Azure SQL Database | `mssql/server` | TDS (SQL Server) | `GET /{account}-sql/servers/{name}/connect` → `port` field |
| Azure Kubernetes Service | `rancher/k3s` | HTTPS (Kubernetes API) | `POST .../listClusterAdminCredential` → `kubeconfigs[0].value` → `server:` in kubeconfig |
| Cosmos MongoDB | `mongo` | MongoDB wire | `GET /{account}-cosmosmongo/connect` → `port` field |
| Cosmos PostgreSQL | `postgres` | PostgreSQL | `GET /{account}-cosmospostgresql/connect` → `port` field |
| Cosmos Cassandra | `cassandra` | CQL | `GET /{account}-cosmoscassandra/connect` → `port` field |
| Cosmos Gremlin | _(embedded)_ | WebSocket | `GET /{account}-cosmosgremlin/connect` |
| Event Hubs (AMQP) | `activemq-artemis` | AMQP 1.0 | Fixed at `5672` (host-bound by Artemis) |
| Event Hubs (Kafka) | `redpanda` | Kafka | Fixed at `9093` (host-bound by Redpanda) |

> **Do NOT add sidecar ports to the `floci-az` service's `ports:` section** in Docker Compose.
> The Docker daemon creates those containers and binds their ports directly to the host.
> Duplicating them on the `floci-az` service would cause port conflicts.

---

## Changing the management port

```yaml
environment:
  FLOCI_AZ_PORT: "14577"
```

When changing the port, also update `FLOCI_AZ_BASE_URL` so URLs embedded in API responses
(SAS tokens, operation locations, JDBC URLs) are correct:

```yaml
environment:
  FLOCI_AZ_PORT: "14577"
  FLOCI_AZ_BASE_URL: "http://localhost:14577"
```

---

## Docker Compose example

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"   # HTTP management plane (all services)
      - "4578:4578"   # HTTPS (Cosmos Java SDK only)
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock  # required for SQL, Functions, Cosmos engines

    # Sidecar ports (SQL Server, MongoDB, Postgres, etc.) bind directly to the host
    # via the Docker daemon — do NOT list them here.
```

Always update `FLOCI_AZ_BASE_URL` together with `FLOCI_AZ_PORT` so URLs embedded in API responses
(SAS tokens, operation locations) point to the right address.
