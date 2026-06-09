# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **acr:** Azure Container Registry emulation (`Microsoft.ContainerRegistry/registries`) — registry lifecycle (CreateOrUpdate, Get, Patch, List by subscription and resource group, Delete), `listCredentials` / `regenerateCredential`, `listUsages`, and `checkNameAvailability`. Non-mocked mode backs all registries with a single shared `registry:2` sidecar exposing the Docker Registry HTTP API V2; registries are isolated by an internal repository prefix, so `loginServer` is path-style (`localhost:{port}/{name}`, not `{name}.azurecr.io`) and standard `docker push`/`pull` work against it. The shared registry runs anonymous (admin credentials are issued but not enforced at the data plane). Registries provision asynchronously (`Creating` → `Succeeded` once `GET /v2/` answers); mocked mode (default in tests) is management-plane only. Compatibility: Terraform/OpenTofu `azurerm_container_registry` suites and a Python data-plane push/pull test
- **redis:** Azure Cache for Redis emulation (`Microsoft.Cache/redis`) — cache lifecycle (CreateOrUpdate, Get, Patch, List by subscription and resource group, Delete) plus `listKeys` and `regenerateKey`. Non-mocked mode backs each cache with a real `valkey/valkey:8-alpine` sidecar container (a drop-in, RESP-compatible Redis fork) that standard Redis clients connect to; the primary access key is the Redis password (`--requirepass`) and both keys authenticate via a `default`-user ACL. Caches provision asynchronously (`Creating` → `Succeeded` once the container answers `PING`); mocked mode (default in tests) is management-plane only. Compatibility: Terraform/OpenTofu `azurerm_redis_cache` suites and a Python redis-py data-plane test
- **appconfig:** 2024-09-01 data-plane parity for the behaviors SDK clients exercise — server-side pagination (`@nextLink` / `Link` header with opaque `after` continuation, 100 items per page), `$select` field projection, `tags` filtering (repeatable, AND semantics) on key-value and revision lists, `Accept-Datetime` time-travel resolved from revision history, a `Sync-Token` consistency header on every response, and true async snapshot provisioning (`PUT` returns `provisioning` + `Operation-Location`; `GET /operations` reports `Succeeded` and flips the snapshot to `ready`) plus conditional `If-Match`/`If-None-Match` on `GetSnapshot`. Adds a `queryParamsMulti` accessor to `AzureRequest` so repeated query params (`tags`) survive routing. Compatibility: extended Java and Python `azure-appconfiguration` suites and a new Node `@azure/app-configuration` suite
- **vm:** Azure Virtual Machines emulation (`Microsoft.Compute/virtualMachines`) — VM lifecycle (CreateOrUpdate, Get, List by subscription and resource group, UpdateTags, Delete), power actions (`start`, `powerOff`, `deallocate`, `restart`, `redeploy`, `reapply`), `instanceView` reporting `ProvisioningState/*` and `PowerState/*`, and `?$expand=instanceView`. Power actions return `202` with an `Azure-AsyncOperation` header and a terminal operation-status endpoint for SDK LRO polling. Mocked mode (default) requires no Docker; container-backed VMs are planned ([#19](https://github.com/floci-io/floci-az/issues/19))
- **arm:** `Microsoft.Network` dependency stubs — virtual networks, subnets, network interfaces (synthesized private IP), public IP addresses, and network security groups, so Terraform's `azurerm_linux_virtual_machine` and its dependencies apply end-to-end
- **arm:** Terraform/OpenTofu compatibility suite extended with a Linux virtual machine and its network dependencies

### Changed

- **acr, redis:** Docker backing is now **on by default** (`mocked: false`) — creating a registry or cache starts a real container (a shared `registry:2` for ACR, a `valkey/valkey:8-alpine` container per cache for Redis). Set `FLOCI_AZ_SERVICES_ACR_MOCKED=true` / `FLOCI_AZ_SERVICES_REDIS_MOCKED=true` to restore management-plane-only mode. Unit tests pin `mocked=true` via test profiles and remain Docker-free.

## [0.5.0] - 2026-05-28

### Added

- **arm:** Azure Resource Manager management-plane emulation — ARM routing on `management.azure.com`-style paths; resource group CRUD (`Microsoft.Resources`); storage account + blob/queue/table endpoint resolution (`Microsoft.Storage`); Key Vault CRUD with vault URI (`Microsoft.KeyVault`); subscription and resource-group list endpoints; OAuth token endpoint (`/oauth2/token`) returning a synthetic bearer token accepted by the ARM routing layer ([#40](https://github.com/floci-io/floci-az/pull/40))
- **arm:** Terraform compatibility — `azurerm` provider `~> 4.0`; `make compat-terraform` target; BATS test suite covering resource group, storage account, storage container, storage queue, Key Vault, and Key Vault secret via Terraform apply/destroy ([#40](https://github.com/floci-io/floci-az/pull/40))
- **arm:** OpenTofu compatibility — identical BATS suite against OpenTofu `tofu` CLI; `make compat-opentofu` target ([#40](https://github.com/floci-io/floci-az/pull/40))

### Fixed

- **blob:** Large blob uploads beyond 20 MB now work correctly — raised Quarkus HTTP body limit to `2G` (`quarkus.http.limits.max-body-size`) and Jackson string-length limit to 512 MB; implemented block blob protocol (`PUT ?comp=block` / `PUT ?comp=blocklist`) so the Azure SDK's chunked multi-part upload path is fully supported ([#41](https://github.com/floci-io/floci-az/pull/41))
- **functions:** All functions in a Function App now share a single container — pool keyed on `appKey` (`account/appName`) instead of per-function; `ContainerLauncher.launch()` injects every function's code at `wwwroot/{funcName}/` and writes a shared `host.json` before the container starts; previously N functions started N containers ([#42](https://github.com/floci-io/floci-az/pull/42))

---

## [0.4.0] - 2026-05-25

### Added

- **tls:** Dynamic self-signed certificate generation at runtime via BouncyCastle — no static cert bundled in the image; certs persist under `data/tls/` and regenerate automatically when hostname config changes (`FLOCI_AZ_HOSTNAME` or `FLOCI_AZ_BASE_URL`)
- **tls:** Protocol-sniffing `TlsProxyServer` — both HTTP and HTTPS served on the same public port `4577`; first byte `0x16` routes to the HTTPS backend, anything else to HTTP
- **tls:** `GET /_floci/tls-cert` endpoint — returns the active TLS certificate PEM so SDK clients and compat tests can dynamically install it into their truststores
- **tls:** `CertificateGenerator` — dedicated class (matching aws-local structure) responsible for X.509 self-signed cert generation with configurable SANs (hostname, IP, wildcard)
- **tls:** `BouncyCastleInitializer` — CDI `@Startup` bean that registers the BouncyCastle JCA provider at application startup
- **event-hubs:** Mocked namespace mode — management API returns `"mocked":true` when no Artemis broker is running; compat tests skip AMQP data-plane assertions gracefully via `Assumptions.assumeTrue`
- **compat (java):** `CosmosCompatibilityTest` now works in Docker compat runs — `EmulatorConfig.installEmulatorTlsCert()` fetches the emulator cert at test setup and installs it into a temp PKCS12 truststore; Netty forced to JDK SSL via `-Dio.netty.handler.ssl.noOpenSsl=true`

### Changed

- **tls:** Replaced static bundled certificates (`src/main/resources/certs/`) with runtime generation — removed `floci-az.crt`, `floci-az.key`, `floci-az.p12` from the image
- **tls:** `TlsConfigSource` delegates cert generation to `CertificateGenerator` instead of inlining BouncyCastle calls
- **build:** Added GraalVM `--initialize-at-run-time` flags for BouncyCastle classes (`DRBG`, `SP800SecureRandom`, `KeyPairGeneratorSpi`, `CertificateFactory`) and `CertificateGenerator` to support native image builds
- **ci:** Compatibility workflow now starts the emulator with `FLOCI_AZ_TLS_ENABLED=true` and `FLOCI_AZ_HOSTNAME=floci-az` — required for Cosmos Java SDK which enforces HTTPS in gateway mode
- **compat (java):** Removed static `floci-az.p12` truststore from test resources — truststore is now built dynamically from the live emulator cert

## [0.3.0] - 2026-05-23

### Added

- **aks:** Azure Kubernetes Service emulation — CreateOrUpdate, Get, Delete, List (by subscription and by resource group), UpdateTags, agent pool CRUD, `listClusterAdminCredential` / `listClusterUserCredential`; ARM path routing on `Microsoft.ContainerService`
- **aks:** Real k3s mode — each cluster starts a privileged `rancher/k3s` container; background readiness poller transitions `provisioningState` from `Creating` → `Succeeded`; kubeconfig with real CA extracted from the container
- **aks:** Mocked mode (`FLOCI_AZ_SERVICES_AKS_MOCKED=true`) — clusters immediately reach `Succeeded` with a synthetic kubeconfig; no Docker required; suitable for unit tests and CI without Docker
- **aks:** `instanceId`-based container naming (`floci-az-aks-{instanceId}`) — 8-char UUID prefix per cluster prevents naming collisions when the same cluster name exists across resource groups
- **aks:** 10 unit tests (`AksHandlerTest`) covering full CRUD in mocked mode; 5 Docker integration tests (`AksDockerTest`) with `@TestProfile(mocked=false)` exercising real k3s start, readiness poll, kubeconfig extraction, and deletion

- **cosmos:** Azure Cosmos DB SQL API emulator — always-on at `/{account}-cosmos/`; databases, containers, and document CRUD; full SQL dialect (`SELECT`, `WHERE`, `ORDER BY`, `GROUP BY`, `OFFSET LIMIT`, `SELECT TOP`, `SELECT DISTINCT`); aggregates (`COUNT`, `SUM`, `AVG`, `MIN`, `MAX`); string, math, array, and type-check functions; named parameters; `PATCH` document operations; transactional batch; server-side pagination with continuation tokens; system properties (`_rid`, `_self`, `_etag`, `_ts`) auto-generated on every write ([#16](https://github.com/floci-io/floci-az/pull/16))
- **cosmos:** Modular multi-API engine support — opt-in per API via environment variable; Docker-backed: MongoDB (`mongo:7`), PostgreSQL/Citus (`citusdata/citus`), Cassandra (`scylladb/scylla:6.2`), Gremlin (`tinkerpop/gremlin-server`); embedded (no Docker): NoSQL in-process SQL engine, Table in-memory OData; each engine exposes a `/connect` endpoint returning its connection string ([#16](https://github.com/floci-io/floci-az/pull/16))
- **cosmos:** HTTPS proxy on port `4578` with bundled self-signed certificate (`CN=localhost`, valid 100 years) — required for the Azure Cosmos DB Java SDK which enforces TLS in gateway mode; no certificate import needed ([#16](https://github.com/floci-io/floci-az/pull/16))
- **cosmos:** Java compatibility tests — `CosmosCompatibilityTest` (SQL API CRUD + queries), `CosmosNoSqlEngineCompatibilityTest` (embedded NoSQL engine), `CosmosMongoEngineCompatibilityTest`, `CosmosPostgresEngineCompatibilityTest`, `CosmosCassandraEngineCompatibilityTest`, `CosmosGremlinEngineCompatibilityTest`, `CosmosTableEngineCompatibilityTest` ([#16](https://github.com/floci-io/floci-az/pull/16))
- **table:** OData `$filter` / `$select` / `$top` query support — operators `eq`, `ne`, `gt`, `ge`, `lt`, `le`, `and`, `or`, `not`; functions `startswith`, `endswith`, `substringof`; typed property annotations (`Edm.Int64`, `Edm.DateTime`, `Edm.Guid`, etc.)
- **table:** ETag optimistic concurrency — `If-Match: *` and `If-Match: "<etag>"` honoured on `PUT`, `MERGE`, `PATCH`, and `DELETE`; `412 Precondition Failed` on mismatch
- **table:** Entity Group Transactions (`$batch`) — atomic execution of multiple operations against a single partition key; full rollback on any failure; standard Azure `multipart/mixed` wire format
- **table:** Server-side pagination with `NextPartitionKey` / `NextRowKey` continuation tokens
- **event-hubs:** Multi-namespace support — each Event Hubs namespace gets its own isolated Artemis container with dynamically allocated ports; default namespace starts on-demand via `PUT /{account}-eventhub/namespaces/{ns}`
- **event-hubs:** Namespace management REST API — `GET/PUT/DELETE /{account}-eventhub/namespaces[/{ns}]`; `GET /{account}-eventhub/namespaces/{ns}/connection` returns AMQP/AMQPS ports and Kafka bootstrap when running; `GET /{account}-eventhub/namespaces/{ns}/tls-cert` returns TLS PEM
- **event-hubs:** ANYCAST + exclusive divert topology embedded in `broker.xml` — durable queues per consumer group ensure messages persist before a receiver connects; Jolokia setup runs asynchronously after broker start
- **event-hubs:** `ArtemisConfigGenerator` generates `broker.xml` per namespace; `ArtemisTlsGenerator` generates self-signed RSA-2048 cert + PKCS12 keystore per namespace for TLS AMQP
- **event-hubs:** Java AMQP compatibility tests (`EventHubCompatibilityTest`, `EventHubNamespaceManagementTest`) replacing previous Python uamqp suite
- **event-hubs:** Kafka (`EventHubsKafkaManager`) starts on-demand when a namespace is created with `kafkaEnabled: true`; idempotent, synchronized, resolves broker address correctly inside and outside Docker
- **docker:** `ContainerSpec`, `ContainerBuilder`, `ContainerLifecycleManager`, `ImageCacheService`, `PortAllocator` ported from floci — shared container infrastructure for sidecar-based services

### Changed

- **docs:** README restructured to match floci (aws-local) format — nav links, "What is?", Features section, SDK examples collapsed per language, Migrating from Azurite, Star History, Contributors
- **docs:** `mkdocs.yml` — added Cosmos DB service page; moved `application.yml Reference` under `Advanced` subsection
- **docker:** Added OCI image labels (`org.opencontainers.image.*`, `io.k8s.*`, `io.openshift.*`) to `Dockerfile.jvm-package` and `Dockerfile.native-package`
- **build:** Removed stale `test-appconfig` Makefile target and `APPCONFIG_DIR` variable — AppConfig tests are covered by `test-python` and `test-java-compat`

## [0.2.0] - 2026-05-15


### Added

- **key-vault:** Azure Key Vault Secrets service — CRUD, versioning (immutable versions with latest pointer), soft-delete lifecycle (delete → recover or purge), properties update (`content_type`, `tags`, `enabled`, `nbf`, `exp`), list secrets/versions/deleted, backup ([#16](https://github.com/floci-io/floci-az/pull/16))
- **key-vault:** 24 Python (`azure-keyvault-secrets 4.11.0`) compatibility tests covering secrets CRUD, versioning, soft-delete, enabled attribute, and backup ([#16](https://github.com/floci-io/floci-az/pull/16))
- **app-config:** Azure App Configuration service — key-values, labels, feature flags, snapshots (frozen KV sets), revisions, ETags, and optimistic-concurrency locks ([#15](https://github.com/floci-io/floci-az/pull/15))
- **app-config:** Snapshot lifecycle — `PUT /snapshots/{name}` captures a frozen set of key-values; `GET /operations?snapshot={name}` returns the LRO result; `GET /kv?snapshot={name}` reads from the frozen set; supports `key` and `key_label` composition modes ([#15](https://github.com/floci-io/floci-az/pull/15))
- **app-config:** 36 Python (`azure-appconfiguration 1.7.1`) and 36 Java compatibility tests covering KV, labels, feature flags, ETags, locks, and snapshots ([#15](https://github.com/floci-io/floci-az/pull/15))
- **docker:** `CurrentContainerNetworkResolver` — detects which Docker network floci-az itself is on when running inside a container, improving function container IP resolution ([#14](https://github.com/floci-io/floci-az/pull/14))
- **docker:** `DockerClientProducer` gains `normalizeDockerHost()` (prepends `tcp://` when scheme is missing) and `resolveEffectiveDockerHost()` (prefers `DOCKER_HOST` env over config default) — fixes connectivity in Bitbucket Pipelines and similar CI environments ([#14](https://github.com/floci-io/floci-az/pull/14))
- **functions:** `FLOCI_AZ_SERVICES_FUNCTIONS_DOCKER_HOST_OVERRIDE` env var — explicitly override the hostname function containers use to reach floci-az ([#14](https://github.com/floci-io/floci-az/pull/14))

### Fixed

- **docker:** `ContainerDetector.hasMountInfoMarkers()` now only checks lines where the filesystem is mounted at root (`/`), preventing false positives in some cgroup configurations ([#14](https://github.com/floci-io/floci-az/pull/14))
- **functions:** `WarmPool` field renamed to `maxPoolSizePerFunction`; eviction scheduler renamed to `evictionScheduler`; idle timeout config key renamed from `idle-timeout-ms` to `container-idle-timeout-seconds` (value now in seconds, default `300`) ([#14](https://github.com/floci-io/floci-az/pull/14))
- **storage:** `HybridStorage`, `PersistentStorage`, and `WalStorage` — replaced `.toList()` with `Collectors.toCollection(ArrayList::new)` for GraalVM native-image compatibility ([#14](https://github.com/floci-io/floci-az/pull/14))

### Dependencies

- Bump `actions/setup-python` from 5 to 6
- Bump `actions/setup-java` from 4 to 5
- Bump `docker/login-action` from 3 to 4
- Bump Maven minor/patch group

---

## [0.1.4] - 2026-04-26

### Fixed

- Release pipeline fix (version bump only; no functional changes)

---

## [0.1.3] - 2026-04-25

### Added

- **docker:** `docker/entrypoint.sh` — gosu-based Docker socket GID fix-up; the `floci` user (uid 1001) is granted access to the Docker socket at runtime, handling both Docker Desktop (macOS/Windows) and native Linux Docker without manual group configuration

### Changed

- **ci:** Release workflow restructured with SHA-pinned actions and a single multi-arch native build (replaces separate per-arch builds)
- **docker:** Dockerfile aligned with floci structure — dedicated `floci` user, correct `/app/data` permissions, ENTRYPOINT wired through `docker/entrypoint.sh`

---

## [0.1.2] - 2026-04-23

### Fixed

- **functions:** Stability improvements — container lifecycle edge cases, improved error handling on function invocation failures ([#9](https://github.com/floci-io/floci-az/pull/9))
- **core:** Log output improvements — cleaner startup banner, structured service-status lines ([#9](https://github.com/floci-io/floci-az/pull/9))
- **docs:** Corrected broken links in documentation

### Changed

- Expanded compatibility test coverage across Blob, Queue, Table, and Functions suites ([#9](https://github.com/floci-io/floci-az/pull/9))

---

## [0.1.1] - 2026-04-22

### Fixed

- Docker image deployment issue in release workflow (multi-arch manifest push)

---

## [0.1.0] - 2026-04-22

### Fixed

- GitHub Actions Java version configuration in release workflow

---

## [0.0.1] - 2026-04-22

### Added

- **blob:** Azure Blob Storage — create/delete containers; upload, download, delete, and list blobs; ETag support
- **queue:** Azure Queue Storage — create/delete queues; send, receive, peek, and delete messages; visibility timeout
- **table:** Azure Table Storage — create/delete tables; insert, get, update, upsert, delete, and list entities; OData filter support
- **functions:** Azure Functions emulation — deploy HTTP-triggered functions via ZIP upload; warm-container pool (LIFO, one container per function); supports `node`, `python`, `java`, and `dotnet` runtimes; Docker-in-Docker via mounted Docker socket
- **storage:** Four pluggable storage backends — `memory` (default), `persistent`, `hybrid`, and `wal`; configurable globally or per service
- **auth:** `dev` mode (accept any credentials) and `strict` mode (validate HMAC-SHA256 shared-key signatures)
- **azfloci:** Companion Python CLI that proxies `az` commands to the local emulator, injecting connection strings automatically
- **compat:** Python (`azure-storage-blob`, `azure-storage-queue`, `azure-data-tables`), Java (Azure SDK BOM 1.2.28), and Node.js (`@azure/storage-blob`, `@azure/storage-queue`, `@azure/data-tables`) compatibility test suites
- Multi-arch Docker image (`linux/amd64`, `linux/arm64`) — native binary (`latest`) and JVM (`latest-jvm`) tags
- Single unified port `4577` for all services

[Unreleased]: https://github.com/floci-io/floci-az/compare/0.5.0...HEAD
[0.5.0]: https://github.com/floci-io/floci-az/compare/0.4.0...0.5.0
[0.4.0]: https://github.com/floci-io/floci-az/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/floci-io/floci-az/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/floci-io/floci-az/compare/0.1.4...0.2.0
[0.1.4]: https://github.com/floci-io/floci-az/compare/0.1.3...0.1.4
[0.1.3]: https://github.com/floci-io/floci-az/compare/0.1.2...0.1.3
[0.1.2]: https://github.com/floci-io/floci-az/compare/0.1.1...0.1.2
[0.1.1]: https://github.com/floci-io/floci-az/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/floci-io/floci-az/compare/0.0.1...0.1.0
[0.0.1]: https://github.com/floci-io/floci-az/releases/tag/0.0.1
