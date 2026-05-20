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
| Cosmos DB (NoSQL — always-on) | `/{account}-cosmos/` |
| Cosmos DB engines (opt-in) | `/{account}-cosmos-{api}/` |
| Key Vault | `/{account}-keyvault/` |
| Event Hubs management | `/{account}-eventhub/` |

## Sidecar ports (bound directly on the host)

When Docker-backed engines or Event Hubs sidecars are enabled, floci-az launches them as sibling
containers via the mounted Docker socket. These containers bind their own ports **directly on the host**
— they are not forwarded through the floci-az container.

| Sidecar | Default port | Controlled by |
|---|---|---|
| Event Hubs AMQP (Artemis) | `5672` | `FLOCI_AZ_SERVICES_EVENT_HUB_AMQP_PORT` |
| Event Hubs Kafka (Redpanda) | `9093` | `FLOCI_AZ_SERVICES_EVENT_HUB_KAFKA_PORT` |
| Cosmos MongoDB engine | `27017` | `FLOCI_AZ_SERVICES_COSMOS_ENGINES_MONGODB_PORT` |
| Cosmos PostgreSQL engine | `5432` | `FLOCI_AZ_SERVICES_COSMOS_ENGINES_POSTGRESQL_PORT` |
| Cosmos Cassandra engine | `9042` | `FLOCI_AZ_SERVICES_COSMOS_ENGINES_CASSANDRA_PORT` |
| Cosmos Gremlin engine | `8182` | `FLOCI_AZ_SERVICES_COSMOS_ENGINES_GREMLIN_PORT` |

> **Do not** publish sidecar ports on the `floci-az` service in `docker-compose.yml`.
> Doing so creates a port conflict when the sidecar container tries to bind the same port.

## Changing the default port

```yaml
environment:
  FLOCI_AZ_PORT: "4580"
  FLOCI_AZ_BASE_URL: "http://localhost:4580"
```

Always update `FLOCI_AZ_BASE_URL` together with `FLOCI_AZ_PORT` so URLs embedded in API responses
(SAS tokens, operation locations) point to the right address.
