# Services Overview

Floci-AZ provides emulation for several core Azure services.

| Service | Endpoint | Implementation Status |
|---|---|---|
| **Blob Storage** | `/{account}/` | ✅ Full CRUD |
| **Queue Storage** | `/{account}-queue/` | ✅ Full CRUD |
| **Table Storage** | `/{account}-table/` | ✅ Full CRUD |
| **Azure Functions** | `/{account}-functions/` | ✅ HTTP Triggers, Docker runtimes |
| **App Configuration** | `/{account}-appconfig/` | ✅ Key-values, labels, feature flags, snapshots, revisions, locks, pagination, `$select`, tags filtering, `Accept-Datetime`, `Sync-Token` |
| **Cosmos DB (SQL API)** | `/{account}-cosmos/` | ✅ Databases, containers, documents CRUD, SQL queries, partition keys |
| **Cosmos DB multi-API** | _(engine sidecars)_ | ✅ MongoDB, PostgreSQL, Cassandra, Gremlin, Table, NoSQL (opt-in Docker engines) |
| **Key Vault** | `/{account}-keyvault/` | ✅ Secrets CRUD, versioning, soft-delete, properties update |
| **Event Hubs** | AMQP `:5672` / Kafka `:9093` | ✅ AMQP 1.0 (Artemis), Kafka-compatible (Redpanda, opt-in) |
| **Service Bus** | `/{account}-servicebus/` + AMQP `:5673` | ✅ Queues, topics, subscriptions (dynamic); AMQP 1.0 via Artemis sidecar or mocked |
| **Azure SQL Database** | ARM path + `/{account}-sql/` | ✅ Servers, databases, firewall rules; Docker-backed SQL Server containers |
| **Azure Kubernetes Service** | ARM path (`Microsoft.ContainerService`) | ✅ Clusters, agent pools, credentials; real k3s containers or mocked |
| **API Management** | ARM path (`Microsoft.ApiManagement`) + `/{account}-apim/` | ✅ APIs, operations, products, subscriptions, named values, backends, OpenAPI import; gateway routing + policy subset |
| **Virtual Network** | ARM path (`Microsoft.Network`) | ✅ VNets, subnets, NICs, public IPs, NSGs; in-process ARM state for Terraform/OpenTofu and VM dependencies |
| **Virtual Machines** | ARM path (`Microsoft.Compute`) | ✅ VM lifecycle (create/start/stop/deallocate/restart/delete/list), instanceView; mocked (Docker backing planned) |
| **Azure Cache for Redis** | ARM path (`Microsoft.Cache`) | ✅ Cache CRUD, listKeys/regenerateKey; real Redis containers (data plane) or mocked |
| **Azure Container Registry** | ARM path (`Microsoft.ContainerRegistry`) | ✅ Registry CRUD, admin credentials, checkNameAvailability; one shared `registry:2` (Docker Registry V2 push/pull) or mocked |
| **Microsoft Entra ID** | `/{tenant}/oauth2/...` + `/.well-known/openid-configuration` | ✅ OpenID Connect provider — RS256-signed tokens, JWKS, discovery; client-credentials + ROPC grants (app registration, Graph, interactive flows planned) |

## Unified Endpoint

All services are accessible through a single port (`4577`). The routing is handled by inspecting the request path and headers.

## Docker-backed services

The following services spin up Docker containers on demand and require the Docker socket:

| Service | Docker image | Data plane |
|---|---|---|
| **Azure Functions** | User-provided image | HTTP to container |
| **Azure SQL Database** | `mcr.microsoft.com/azure-sql-edge:latest` | TDS direct to container port |
| **Cosmos DB engines** | Various (mongo, postgres, cassandra, …) | Protocol direct to container port |
| **Azure Kubernetes Service** | `rancher/k3s:latest` | kubectl direct to k3s API server port |

> These services **must** have access to the Docker daemon (`/var/run/docker.sock` mount in Docker Compose).
