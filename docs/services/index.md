# Services Overview

Floci-AZ provides emulation for several core Azure services.

| Service | Endpoint | Implementation Status |
|---|---|---|
| **Blob Storage** | `/{account}/` | ✅ Full CRUD |
| **Queue Storage** | `/{account}-queue/` | ✅ Full CRUD |
| **Table Storage** | `/{account}-table/` | ✅ Full CRUD |
| **Azure Functions** | `/{account}-functions/` | ✅ HTTP Triggers, Docker runtimes |
| **App Configuration** | `/{account}-appconfig/` | ✅ Key-values, labels, feature flags, snapshots, revisions, locks |
| **Cosmos DB (SQL API)** | `/{account}-cosmos/` | ✅ Databases, containers, documents CRUD, SQL queries, partition keys |

## Unified Endpoint

All services are accessible through a single port (`4577`). The routing is handled by inspecting the request path and headers.
