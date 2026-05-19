# Services Overview

Floci-AZ provides emulation for several core Azure services.

| Service | Endpoint | Implementation Status |
|---|---|---|
| **Blob Storage** | `/{account}/` | ✅ Full CRUD |
| **Queue Storage** | `/{account}-queue/` | ✅ Full CRUD |
| **Table Storage** | `/{account}-table/` | ✅ Full CRUD |
| **Azure Functions** | `/{account}-functions/` | ✅ HTTP Triggers, Docker runtimes |
| **App Configuration** | `/{account}-appconfig/` | ✅ Key-values, labels, feature flags, snapshots, revisions, locks |
| **Cosmos DB (NoSQL — always-on)** | `/{account}-cosmos/` | ✅ Databases, containers, documents CRUD, full SQL dialect, PATCH, transactional batch |
| **Cosmos DB engines (opt-in)** | `/{account}-cosmos-{api}/` | ✅ MongoDB · PostgreSQL · Cassandra · Gremlin (Docker-backed) · Table · NoSQL (embedded) |
| **Key Vault** | `/{account}-keyvault/` | ✅ Secrets CRUD, versioning, soft-delete, properties update |
| **Event Hubs** | AMQP `:5672` / Kafka `:9093` | ✅ AMQP 1.0 (Artemis sidecar), Kafka-compatible (Redpanda, opt-in) |

## Unified Endpoint

All services are accessible through a single port (`4577`). The routing is handled by inspecting the request path and headers.
