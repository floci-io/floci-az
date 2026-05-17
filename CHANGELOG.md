# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **event-hubs:** Multi-namespace support — each Event Hubs namespace gets its own isolated Artemis container with dynamically allocated ports, analogous to how the AWS emulator creates one PostgreSQL container per RDS instance; default namespace starts automatically from config
- **event-hubs:** Namespace management REST API — `GET/PUT/DELETE /{account}-eventhub/namespaces[/{ns}]`; `GET /{account}-eventhub/namespaces/{ns}/connection` returns AMQP/AMQPS ports; `GET /{account}-eventhub/namespaces/{ns}/tls-cert` returns TLS PEM
- **event-hubs:** `EventHubNamespaceManager` (`@ApplicationScoped`) manages the namespace lifecycle map; `ArtemisManager` removed — its logic is now namespaced in `EventHubNamespaceManager`
- **event-hubs:** Azure Event Hubs emulation — AMQP 1.0 via Apache ActiveMQ Artemis sidecar (port 5672); TLS AMQP via port 5671 for uamqp/Python SDK; Kafka-compatible access via Redpanda sidecar (port 9093, opt-in with `kafkaEnabled: true`); namespace and event hub entities declared via `entities` config; `$Default` consumer group created automatically
- **event-hubs:** ANYCAST + exclusive divert topology provisioned at startup via Artemis Jolokia API — durable queues per consumer group ensure messages persist before a receiver connects
- **event-hubs:** `ArtemisConfigGenerator` generates `broker.xml` per namespace; `ArtemisTlsGenerator` generates self-signed RSA-2048 cert + PKCS12 keystore per namespace for TLS AMQP
- **event-hubs:** 6 Python (`uamqp`) compatibility tests covering send/receive, consumer groups, partitions, and high-throughput delivery
- **docker:** `ContainerSpec`, `ContainerBuilder`, `ContainerLifecycleManager`, `ImageCacheService`, `PortAllocator` ported from floci — shared container infrastructure for sidecar-based services

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

[Unreleased]: https://github.com/floci-io/floci-az/compare/0.2.0...HEAD
[0.2.0]: https://github.com/floci-io/floci-az/compare/0.1.4...0.2.0
[0.1.4]: https://github.com/floci-io/floci-az/compare/0.1.3...0.1.4
[0.1.3]: https://github.com/floci-io/floci-az/compare/0.1.2...0.1.3
[0.1.2]: https://github.com/floci-io/floci-az/compare/0.1.1...0.1.2
[0.1.1]: https://github.com/floci-io/floci-az/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/floci-io/floci-az/compare/0.0.1...0.1.0
[0.0.1]: https://github.com/floci-io/floci-az/releases/tag/0.0.1
