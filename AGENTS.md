Guidance for AI coding agents working in the floci-az repository.

This file defines repository-specific operating rules for autonomous or semi-autonomous coding agents. Follow these instructions unless a maintainer explicitly tells you otherwise.

---

## Project Overview

floci-az is a Java-based local Azure emulator built on Quarkus.

Its goal is full Azure SDK and Azure CLI compatibility through real Azure wire protocols, not convenience APIs or simplified abstractions.

floci-az is the Azure counterpart of Floci (the AWS emulator) and floci-gcp. They share design patterns and Docker infrastructure, but are independent repositories.

- Port: **4577** (all HTTP services share this single port)
- AMQP port: **5672** (Event Hubs, managed by Artemis sidecar)
- Kafka port: **9093** (Event Hubs Kafka, managed by Redpanda sidecar, opt-in)
- Stack:
  - Java 25
  - Quarkus 3.x
  - JUnit 5 + RestAssured
  - Jackson
  - docker-java for sidecar container management

---

## First Principles

When making changes, follow these priorities:

1. Preserve Azure protocol compatibility
2. Match Azure SDK and CLI behavior
3. Reuse existing floci-az patterns
4. Prefer correctness over convenience
5. Keep changes narrow and testable

Critical rules:

- Do not introduce custom endpoint shapes
- Do not change request or response formats for convenience
- Do not perform broad refactors unless the task explicitly requires them
- Keep behavior aligned with Azure SDK expectations and existing floci-az conventions

---

## Architecture

floci-az follows a layered design:

- **AzureRoutingFilter** — pre-matching JAX-RS filter; extracts account name and service type from the request path; dispatches to the correct `AzureServiceHandler`
- **AzureServiceHandler** — interface implemented by each service (`getServiceType()`, `canHandle()`, `handle()`)
- **AzureServiceRegistry** — CDI registry of all handlers; checks `isEnabled()` per service
- **Service Handler** — parses Azure protocol input, contains business logic, produces Azure-compatible responses
- **StorageBackend** — pluggable persistence (memory / persistent / hybrid / wal)

### Core Infrastructure

- `EmulatorConfig` — SmallRye `@ConfigMapping`; prefix `floci-az`
- `AzureRoutingFilter` — path-based routing (suffix detection: `-queue`, `-table`, `-functions`, `-appconfig`, `-keyvault`, `-eventhub`)
- `AzureServiceRegistry` — handler discovery + `isEnabled()` per service type
- `BannerLogger` — startup banner listing all enabled services
- `StorageBackend` + `StorageFactory` — pluggable storage
- `XmlBuilder` — fluent XML builder with attribute support (`startAttr`, `selfClose`)
- `XmlParser` — StAX-based XML parser (no extra dependencies)
- `XmlUtils` — Jackson-based XML serialisation for structured models

### Sidecar Container Infrastructure (`core/docker/`)

Used by services that delegate to a managed Docker container:

- `ContainerSpec` — immutable container descriptor (record)
- `ContainerBuilder` — fluent `ContainerSpec` builder (network, ports, mounts, log rotation, DNS)
- `ContainerLifecycleManager` — create/start/stop/remove containers; volume management; endpoint resolution
- `ImageCacheService` — pull-once-per-image with registry credential support
- `PortAllocator` — thread-safe free TCP port allocation
- `ContainerDetector` — detects whether floci-az is running inside Docker
- `CurrentContainerNetworkResolver` — resolves which Docker network floci-az is on
- `DockerHostResolver` — resolves the correct host for child containers to reach floci-az
- `DockerClientProducer` — CDI producer for `DockerClient`

---

## Package Layout

```
io.floci.az.config           — EmulatorConfig
io.floci.az.core             — routing, registry, banner, XML utils, auth, DNS
io.floci.az.core.auth        — auth pipeline, verifiers
io.floci.az.core.docker      — container infrastructure
io.floci.az.core.storage     — storage backends
io.floci.az.services.<svc>   — per-service handlers
```

Typical service structure:

```
services/<svc>/
  *Handler.java       — implements AzureServiceHandler
  *Models.java        — domain objects (optional)
```

Sidecar-based services additionally have:

```
services/<svc>/
  *ContainerManager.java   — @ApplicationScoped, @PostConstruct/@PreDestroy lifecycle
  *Manager.java            — manages one sidecar (pull, start, health-wait, stop)
  *ConfigGenerator.java    — generates sidecar config file from EmulatorConfig
```

Rule: copy an existing service pattern before introducing a new one.

---

## Azure Protocol Rules

| Service | Protocol | Path Suffix | Notes |
|---|---|---|---|
| Blob Storage | Azure Storage REST | `/{account}/` | XML responses, Shared Key auth |
| Queue Storage | Azure Storage REST | `/{account}-queue/` | XML responses |
| Table Storage | Azure Storage REST | `/{account}-table/` | JSON / OData |
| Azure Functions | HTTP REST | `/{account}-functions/` | ZIP deploy, HTTP trigger invoke |
| App Configuration | Azure App Config REST | `/{account}-appconfig/` | JSON, ETags, labels |
| Key Vault | Azure Key Vault REST | `/{account}-keyvault/` | JSON, Bearer auth challenge |
| Event Hubs | AMQP 1.0 (Artemis) | port 5672 | Direct sidecar, not proxied through 4577 |
| Event Hubs Kafka | Kafka (Redpanda) | port 9093 | Optional, direct sidecar |

### Key Vault auth challenge

The azure-keyvault SDK uses `ChallengeAuthPolicy`: it sends a probe request without a body to elicit a `401 WWW-Authenticate` response. The handler must return `401` when no `Authorization` header is present. Always include `verify_challenge_resource=False` in SDK test clients (localhost can never match `.vault.azure.net`).

### Event Hubs

Event Hubs does **not** use the HTTP port for data-plane traffic. All SDK send/receive goes over AMQP 1.0 directly to the Artemis sidecar on port 5672. The `/{account}-eventhub/` path at port 4577 is admin/health only.

---

## XML / JSON Rules

- Use `XmlBuilder` for XML responses (supports `start`, `startAttr`, `selfClose`, `elem`, `raw`, `end`)
- Use `XmlParser` for XML parsing; do not use regex or string manipulation for XML
- Use `XmlUtils` for Jackson-based serialisation of annotated model classes
- JSON errors must follow Azure error structures (`{"error":{"code":"...","message":"..."}}`)
- Types returned from handlers must remain reflection-safe for GraalVM native image

---

## Storage Rules

Supported storage modes: `memory`, `persistent`, `hybrid`, `wal`

Rules:

- Always use `StorageFactory` to obtain a `StorageBackend`
- Do not instantiate storage implementations directly inside handlers
- Respect lifecycle hooks for load and flush behavior
- Event Hubs does not use `StorageBackend` — it delegates entirely to the Artemis sidecar

When adding storage-related behavior:

1. Update `EmulatorConfig` (add `ServiceStorageConfig` accessor)
2. Update `ServicesStorageConfig` in `EmulatorConfig`
3. Update main `application.yml`
4. Wire through `StorageFactory`
5. Add storage mode to `BannerLogger.getStorageMode()`

---

## Configuration Rules

Configuration lives under `floci-az.*` and maps to `EmulatorConfig` via SmallRye `@ConfigMapping`.

When adding config:

1. Add interface method to `EmulatorConfig` (inner interface or new `@WithDefault`)
2. Add to `ServicesConfig` if service-scoped
3. Add to main `application.yml`
4. Update `BannerLogger` if user-visible
5. Update documentation if user-facing
6. Follow `FLOCI_AZ_*` environment variable conventions (SmallRye converts `.` → `_` and lowercases)

Critical areas:

- `base-url` / `hostname` — affects URLs returned in API responses
- `port` — default 4577
- `storage.mode` — global default; per-service override via `storage.services.<svc>.mode`
- `services.docker-network` — shared Docker network for sidecar containers
- `docker.docker-host` — Docker daemon socket

---

## Sidecar Container Rules

Services that use sidecar containers (Event Hubs, Service Bus, SQL, PostgreSQL, Redis, ACR, AKS, ...) follow this pattern:

1. `*ContainerManager` observes `StartupEvent` and calls `*Manager.start()`; `@PreDestroy` calls `stop()`
2. Startup failures must be **non-fatal** — log an error and return; do not throw and crash the app
3. Always call `lifecycleManager.removeIfExists(containerName)` before creating to clean up stale containers
4. Use `ContainerBuilder` + `ContainerLifecycleManager` — do not call `dockerClient` directly in new code
5. Health-wait after start: TCP poll for AMQP/port-based services; HTTP poll for services with a `/ready` endpoint
6. Config files generated for sidecars (e.g. `broker.xml`) must use `XmlBuilder` for XML, not string concatenation

---

## Build & Run

```bash
./mvnw quarkus:dev
./mvnw test
./mvnw clean package
./mvnw clean package -DskipTests
```

### Focused tests

```bash
./mvnw test -Dtest=HealthTest
./mvnw test -Dtest=BlobServiceTest#testUploadBlob
```

### Manual testing

```bash
make run        # start emulator in background (for manual SDK testing)
make test       # run unit tests + all compat tests (starts its own Docker emulator)
make stop
```

---

## Compatibility Test Suite

```
compatibility-tests/
  sdk-test-python/      — Azure SDK for Python (pytest): blob/queue/table, cosmos, vm, redis, acr,
                          managed identity, plus appconfig/ and keyvault/ sub-suites
  sdk-test-java/        — Azure SDK for Java (BOM; the primary compat target): blob/queue/table,
                          cosmos + all engines, functions, keyvault, appconfig, eventhub,
                          servicebus, eventgrid, apim, sql, postgres, vm, datalake,
                          managed identity
  sdk-test-node/        — Azure SDK for JS (jest): blob, queue, table, cosmos, appconfig,
                          keyvault, eventhub, managed identity
  compat-terraform/     — hashicorp/azurerm Terraform provider (BATS)
  compat-opentofu/      — OpenTofu with the azurerm provider (BATS)
  compat-azcli/         — real `az` CLI against a custom registered cloud (BATS)
```

Guidelines:

- Always validate new implementations against the relevant SDK test suite
- Prefer SDK clients over raw HTTP for all validation
- Use `make test-<service>` to run a specific suite
- Use `make compat-docker` to run all suites against a running container

### Keeping the Makefile and CI in sync

The Makefile and the CI matrix (`.github/workflows/compatibility.yml`) both run the SDK test containers against floci-az and must pass the same environment variables per suite.

**Rule: a suite's env vars must be identical across every `docker run` for that suite in the Makefile and the corresponding matrix `extra_env` entry in `.github/workflows/compatibility.yml`. Define them once (a shared variable), never inline per call site.**

Suite-internal defaults (`FLOCI_AZ_ENDPOINT`, `EVENTHUB_*`, `JEST_JUNIT_*`) are baked as `ENV` in each suite's Dockerfile with the compat-network values — they do NOT belong in the Makefile or `extra_env`. Only network-topology overrides (sidecar hostnames/ports, emulator-endpoint redirects) go there.

Current per-suite env vars that must stay in sync:

| Suite | Makefile | CI (`compatibility.yml` `extra_env`) |
|---|---|---|
| `sdk-test-java` | `-e SERVICEBUS_HOST=floci-az-servicebus-default` | ✓ |
| `sdk-test-java` | `-e SERVICEBUS_AMQPS_PORT=5671` | ✓ |
| `sdk-test-java` | `-e SERVICEBUS_NAMESPACE=default` | ✓ |
| `sdk-test-java` | `-e AZURE_POD_IDENTITY_AUTHORITY_HOST=http://floci-az:4577` | ✓ |
| `sdk-test-python` | `-e AZURE_POD_IDENTITY_AUTHORITY_HOST=http://floci-az:4577` | ✓ |
| `sdk-test-node` | `-e AZURE_POD_IDENTITY_AUTHORITY_HOST=http://floci-az:4577` | ✓ |

The container name follows the pattern `floci-az-<service>-<namespace>` (e.g. `floci-az-servicebus-default`). If a new sidecar-based service is added, its container name and port must be added to both places.

---

## Testing Rules

### Conventions

- Unit/integration tests: `src/test/java/io/floci/az/`
- Compatibility tests: `compatibility-tests/sdk-test-*/`
- Unit tests use `@QuarkusTest` + RestAssured
- Sidecar-based services are disabled in unit tests via graceful Docker failure handling (no Docker socket in CI)

### When touching protocol behavior

If a change affects request parsing, response shape, error handling, persistence semantics, URL generation, or service enablement:

1. Add or update automated tests
2. Prefer SDK-based verification in `compatibility-tests/`
3. Check behavior across all relevant SDK languages
4. Document intentional deviations clearly

---

## Error Handling

- Handlers return `Response` with Azure-compatible error JSON: `{"error":{"code":"...","message":"..."}}`
- Use `AzureErrorResponse` helper for XML-format errors (Storage services)
- Sidecar startup failures must degrade gracefully — log `ERROR` and skip, never throw from `onStart`

---

## Service Implementation Pattern

When adding a new HTTP-based service:

1. Create `services/<svc>/` package
2. Add `*Handler.java` implementing `AzureServiceHandler`
3. Add suffix detection in `AzureRoutingFilter` (strip suffix, set `serviceType`)
4. Add `case "<svc>" -> config.services().<svc>().enabled();` in `AzureServiceRegistry.isEnabled()`
5. Add config interface to `EmulatorConfig.ServicesConfig`
6. Add YAML block to `application.yml`
7. Add storage wiring if needed
8. Update `BannerLogger`
9. Add compatibility tests
10. Update docs (`docs/services/<svc>.md`, `docs/services/index.md`, `mkdocs.yml`, `README.md`, `CHANGELOG.md`)

When adding a sidecar-based service, additionally:

- Implement `*ContainerManager`, `*Manager`, `*ConfigGenerator`
- Expose sidecar ports in the Docker Compose example

---

## Code Style

### Dependency injection

- **Always prefer constructor injection (`@Inject` on the constructor) over field injection (`@Inject` on fields)** in new or modified code:
  - dependencies become `private final`, so the object is immutable and never observed half-constructed
  - required collaborators are explicit in the signature, and plain unit tests can construct the class without a CDI container
  - with Quarkus Arc, normal-scoped beans are client proxies — reading *fields* through an injected bean hits the proxy's own field copies, not the contextual instance. Always go through methods on injected beans; constructor injection plus method access avoids this trap entirely
- When touching a class that still uses field injection, migrate it to constructor injection if the change is small; otherwise stay consistent within the class and flag it

### General practices

- Make fields `final` wherever possible; avoid static mutable state
- Never leave a `catch` block empty — if an exception is intentionally tolerated, log it with enough context to diagnose later
- Validate inputs at the protocol boundary and fail fast with the correct Azure error shape
- Keep methods small and focused; extract helpers instead of growing switch/if ladders
- Prefer self-explanatory code over comments; only add a comment when the WHY is non-obvious
- Always use braces in conditionals
- Follow existing project patterns before introducing new abstractions
- Use modern Java features when they improve clarity
- Use Java records for simple data carriers; model Azure resources as typed domain objects when practical

---

## Logging

- Use JBoss Logging (`Logger.getLogger(MyClass.class)`)
- Use `LOG.infov(...)` / `LOG.debugv(...)` for parameterised messages
- Keep logs structured; avoid noisy logs in hot paths
- Sidecar startup/stop should always log at INFO level

---

## Pull Request Guidelines

- Keep changes focused; avoid unrelated refactors
- Preserve behavior unless the task explicitly requires change
- Update docs when functionality changes
- Explain missing tests when behavior changed but no automated coverage was added

Conventional commits:

- `feat:` — new service or feature
- `fix:` — bug fix
- `perf:` — performance improvement
- `docs:` — documentation only
- `chore:` — build, CI, dependencies

Do not add `Co-Authored-By` trailers for AI tools in commit messages.

---

## Azure Protocol References

When in doubt about Azure wire protocol behavior, refer to real sources:

- [Azure REST API specs](https://github.com/Azure/azure-rest-api-specs) — the authoritative request/response/error contract
- Azure SDK source code ([Java](https://github.com/Azure/azure-sdk-for-java), [Python](https://github.com/Azure/azure-sdk-for-python), [JS](https://github.com/Azure/azure-sdk-for-js))
- Microsoft official emulator behavior (Azurite for Storage, the official Event Hubs emulator)

Do not invent protocol behavior. If a spec is ambiguous, test against a real Azure endpoint or the official emulator.

---

## Do Not

- Do not introduce non-Azure endpoint shapes
- Do not bypass `StorageFactory` for storage operations
- Do not call `dockerClient` directly in new service code — use `ContainerBuilder` + `ContainerLifecycleManager`
- Do not make sidecar startup failures fatal (they would break unit tests and environments without Docker)
